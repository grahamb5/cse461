package edu.uw.cs.cse461.net.rpc;

import java.io.IOException;
import java.net.SocketException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.json.JSONException;
import org.json.JSONObject;

import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableService;
import edu.uw.cs.cse461.net.rpc.RPCMessage.RPCCallMessage.RPCControlMessage;
import edu.uw.cs.cse461.net.rpc.RPCMessage.RPCCallMessage.RPCInvokeMessage;
import edu.uw.cs.cse461.net.tcpmessagehandler.TCPMessageHandler;
import edu.uw.cs.cse461.util.Log;

/**
 * Class implementing the caller side of RPC -- the RPCCall.invoke() method.
 * The invoke() method itself is static, for the convenience of the callers,
 * but this class is a normal, loadable, service.
 * <p>
 * <p>
 * This class is responsible for implementing persistent connections. 
 * (What you might think of as the actual remote call code is in RCPCallerSocket.java.)
 * Implementing persistence requires keeping a cache that must be cleaned periodically.
 * We do that using a cleaner thread.
 * 
 * @author zahorjan
 *
 */
public class RPCCall extends NetLoadableService {
	private static final String TAG="RPCCall";
	
	// Map from service name to pair of message handler and keep alive boolean value
	private static ServiceManager services;

	//-------------------------------------------------------------------------------------------
	//-------------------------------------------------------------------------------------------
	// The static versions of invoke() are just a convenience for caller's -- it
	// makes sure the RPCCall service is actually running, and then invokes the
	// the code that actually implements invoke.
	
	/**
	 * Invokes method() on serviceName located on remote host ip:port.
	 * @param ip Remote host's ip address
	 * @param port RPC service port on remote host
	 * @param serviceName Name of service to be invoked
	 * @param method Name of method of the service to invoke
	 * @param userRequest Arguments to call
	 * @param socketTimeout Maximum time to wait for a response, in msec.
	 * @return Returns whatever the remote method returns.
	 * @throws JSONException
	 * @throws IOException
	 */
	public static JSONObject invoke(
			String ip,				  // ip or dns name of remote host
			int port,                 // port that RPC is listening on on the remote host
			String serviceName,       // name of the remote service
			String method,            // name of that service's method to invoke
			JSONObject userRequest,   // arguments to send to remote method,
			int socketTimeout         // timeout for this call, in msec.
			) throws JSONException, IOException {
		RPCCall rpcCallObj =  (RPCCall)NetBase.theNetBase().getService( "rpccall" );
		if ( rpcCallObj == null ) throw new IOException("RPCCall.invoke() called but the RPCCall service isn't loaded");
		return rpcCallObj._invoke(ip, port, serviceName, method, userRequest, socketTimeout, true);
	}
	
	/**
	 * A convenience implementation of invoke() that doesn't require caller to set a timeout.
	 * The timeout is set to the net.timeout.socket entry from the config file, or 2 seconds if that
	 * doesn't exist.
	 */
	public static JSONObject invoke(
			String ip,				  // ip or dns name of remote host
			int port,                 // port that RPC is listening on on the remote host
			String serviceName,       // name of the remote service
			String method,            // name of that service's method to invoke
			JSONObject userRequest    // arguments to send to remote method,
			) throws JSONException, IOException {
		int socketTimeout  = NetBase.theNetBase().config().getAsInt("net.timeout.socket", 2000);
		return invoke(ip, port, serviceName, method, userRequest, socketTimeout);
	}

	//-------------------------------------------------------------------------------------------
	//-------------------------------------------------------------------------------------------
	
	/**
	 * The infrastructure requires a public constructor taking no arguments.  Plus, we need a constructor.
	 */
	public RPCCall() {
		super("rpccall");
		services = new ServiceManager();
	}

	/**
	 * This private method performs the actual invocation, including the management of persistent connections.
	 * Note that because we may issue the call twice, we  may (a) cause it to be executed twice at the server(!),
	 * and (b) may end up blocking the caller for around twice the timeout specified in the call. (!)
	 * 
	 * @param ip
	 * @param port
	 * @param serviceName
	 * @param method
	 * @param userRequest
	 * @param socketTimeout Max time to wait for this call
	 * @param tryAgain Set to true if you want to repeat call if a socket error occurs; e.g., persistent socket is no good when you use it
	 * @return
	 * @throws JSONException
	 * @throws IOException
	 */
	private JSONObject _invoke(
			String ip,				  // ip or dns name of remote host
			int port,                 // port that RPC is listening on on the remote host
			String serviceName,       // name of the remote service
			String method,            // name of that service's method to invoke
			JSONObject userRequest,   // arguments to send to remote method
			int socketTimeout,        // max time to wait for reply
			boolean tryAgain          // true if an invocation failure on a persistent connection should cause a re-try of the call, false to give up
			) throws JSONException, IOException {
		
		
		// get the TCPMessageHandler associated with the service
		TCPMessageHandler msgHandle = services.getService(serviceName, ip, port, socketTimeout);
		
		// we need to send the call now
		// first construct the JSONObject that will get sent
		RPCMessage sendMsg = new RPCInvokeMessage(serviceName, method, userRequest);
		String msgString = sendMsg.toString();
		
		try {
			// send the invoking call
			msgHandle.sendMessage(sendMsg.marshall());
		} catch (Exception e) {
			// retry if we should
			if (tryAgain) {
				// get the service again incase it timed out
				msgHandle = services.resetService(serviceName, ip, port, socketTimeout);
				msgHandle.sendMessage(sendMsg.marshall());
			}
		}

		// receive the response
		RPCMessage recMsg = RPCMessage.unmarshall(msgHandle.readMessageAsString());
		msgString = recMsg.toString();
		
		// check if it is a good response
		if (recMsg.type() == "ERROR" || recMsg.type() != "OK") {
			throw new IOException("Invoke - Expected type 'OK' but got type " + recMsg.type());
		}
		
		// remove the service if we don't keep it alive
		services.removeService(serviceName);
		
		JSONObject value = recMsg.marshall().optJSONObject("value");
		if (value == null) {
			throw new IOException("Invoke - Expected value but got null");
		}
		
		return value;
	}
	
	
	
	@Override
	// clear all persistent connections
	public void shutdown() {
		services.shutdown();
	}
	
	@Override
	public String dumpState() {
		String message = "Current persistent connections are ...\n";
		for (String servName : services.keySet()) {
			message = message.concat(servName + "\n");
		}
		return message;
	}
	
	// class to manage persistent services
	private class ServiceManager {
		private Map<String, ServiceState<TCPMessageHandler, Boolean, TimerTask>> services;
		private Timer timer;
		
		public ServiceManager() {
			services = new HashMap<String, ServiceState<TCPMessageHandler, Boolean, TimerTask>>();
			timer = new Timer();
		}
		
		public Set<String> keySet() {
			return services.keySet();
		}
		
		// resets a service if there was an error
		public TCPMessageHandler resetService(String serviceName, String ip, int port, int socketTimeout) throws JSONException, IOException {
			// try and send some data
			try {
				if (services.containsKey(serviceName)) {
					services.get(serviceName).handler.sendMessage(0);
				}
			} catch (IOException e) {
				// persistence dropped at the other end, reset
				services.remove(serviceName);
				return getService(serviceName, ip, port, socketTimeout);
			}
			return getService(serviceName, ip, port, socketTimeout);
		}
		
		// gets a service by establishing it or returning an active one
		public TCPMessageHandler getService(String serviceName, String ip, int port, int socketTimeout) throws JSONException, IOException {
		
			// return the service if there is already one active
			if (services.containsKey(serviceName)) {
				// reset the persistence timeout
				ServiceState<TCPMessageHandler, Boolean, TimerTask> state = services.get(serviceName);
				state.timertask.cancel();
				state.timertask = new PersistenceTask(serviceName);
				timer.schedule(state.timertask, NetBase.theNetBase().config().getAsInt("rpc.persistence.timeout", 30000));
				timer.purge();
				return state.handler;
			}
			// otherwise make a new service, add it to the services map and return it
			// create a socket and message handler for sending messages
			// also setup the service with a handshake
			RPCCallerSocket callSocket = new RPCCallerSocket(ip, port, false);
			TCPMessageHandler msgHandle = new TCPMessageHandler(callSocket);
			msgHandle.setTimeout(socketTimeout);
			msgHandle.setMaxReadLength(Integer.MAX_VALUE);
			
			// handshake
			JSONObject options = new JSONObject().put("connection", "keep-alive");
			RPCMessage sendMsg = new RPCControlMessage("connect", options);
			String msgString = sendMsg.toString();
			msgHandle.sendMessage(sendMsg.marshall());
			RPCMessage recMsg = RPCMessage.unmarshall(msgHandle.readMessageAsString());
			msgString = recMsg.toString();
			// check good handshake
			if (recMsg.type() == "ERROR" || recMsg.type() != "OK") {
				throw new IOException("Handshake - Expected type 'OK' but got type " + recMsg.type());
			}
			
			// should we keep this connection alive or not
			
			boolean keepAlive = recMsg.marshall().getJSONObject("value").getString("connection").equals("keep-alive");
			
			services.put(serviceName, new ServiceState<TCPMessageHandler, Boolean, TimerTask>(msgHandle, keepAlive, new PersistenceTask(serviceName)));
			timer.schedule(services.get(serviceName).timertask, NetBase.theNetBase().config().getAsInt("rpc.persistence.timeout", 30000));
			return msgHandle;
		}
		
		// removes a service if it is not persistent
		public ServiceState<TCPMessageHandler, Boolean, TimerTask> removeService(String serviceName) {
			// remove the service if it exists and is not persistent 
			if (services.containsKey(serviceName)) {
				if (!services.get(serviceName).persistence)
					return services.remove(serviceName);
			}
			return null;
		}
		
		// shutdown the services
		public void shutdown() {
			// close each connection
			for (ServiceState<TCPMessageHandler, Boolean, TimerTask> serv : services.values()) {
				serv.handler.close();
				serv.timertask.cancel();
			}
			
			// clear the map
			for (String servName : services.keySet()) {
				services.remove(servName);
			}
			timer.purge();
			timer.cancel();
		}
		
		// timer task to be executed on persistence timeout
		private class PersistenceTask extends TimerTask {
			private String serviceName;
			
			PersistenceTask(String serviceName) {
				super();
				this.serviceName = serviceName;
			}

			@Override
			public void run() {
				services.remove(serviceName);
			}
			
		}
		
	}
	
	// holds the state for a service
	private class ServiceState<H, P, T> {
	    private H handler;
	    private P persistence;
	    private T timertask;

	    public ServiceState(H handler, P persistence, T timertask) {
	        this.handler = handler;
	        this.persistence = persistence;
	        this.timertask = timertask;
	    }

	    
	}
}
