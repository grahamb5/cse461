package edu.uw.cs.cse461.service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.base.NetLoadableInterface.NetLoadableServiceInterface;
import edu.uw.cs.cse461.util.ConfigManager;
import edu.uw.cs.cse461.util.IPFinder;
import edu.uw.cs.cse461.util.Log;

/**
 * Transfers reasonably large amounts of data to client over raw TCP and UDP sockets.  In both cases,
 * the server simply sends as fast as it can.  The server does not implement any correctness mechanisms,
 * so, when using UDP, clients may not receive all the data sent.
 * <p>
 * Four consecutive ports are used to send fixed amounts of data of various sizes.
 * <p>
 * @author zahorjan
 *
 */
public class DataXferRawService extends DataXferServiceBase implements NetLoadableServiceInterface {
	private static final String TAG="DataXferRawService";

	public static final int NPORTS = 4;
	public static final int[] XFERSIZE = {1000, 10000, 100000, 1000000};

	private int mBasePort;

	public DataXferRawService() throws Exception {
		super("dataxferraw");
		
		ConfigManager config = NetBase.theNetBase().config();
		mBasePort = config.getAsInt("dataxferraw.server.baseport", 0);
		if ( mBasePort == 0 ) throw new RuntimeException("dataxferraw service can't run -- no dataxferraw.server.baseport entry in config file");
		//TODO: implement this method (hint: look at echo raw service)

		for (int i = 0 ; i < 4 ; i++) {
			new DgramThread(mBasePort + i).start();
			new TcpThread(mBasePort + i).start();
		}
	}


	/**
	 * Returns string summarizing the status of this server.  The string is printed by the dumpservicestate
	 * console application, and is also available by executing dumpservicestate through the web interface.
	 */
	@Override
	public String dumpState() {
		//TODO: not necessary, but filling this in is useful
		return "";

	}

	private class DgramThread extends Thread {
		int port;
		private DatagramSocket mDatagramSocket;

		DgramThread(int portnum) throws Exception {
			this.port = portnum;

			String serverIP = IPFinder.localIP();
			if ( serverIP == null ) throw new Exception("IPFinder isn't providing the local IP address.  Can't run.");
			this.mDatagramSocket = new DatagramSocket(new InetSocketAddress(serverIP, port));
			this.mDatagramSocket.setSoTimeout(NetBase.theNetBase().config().getAsInt("net.timeout.granularity", 500));

			Log.i(TAG,  "Datagram socket = " + mDatagramSocket.getLocalSocketAddress());
		}

		public void run() {
			byte receiveBuf[] = new byte[HEADER_LEN];

			DatagramPacket packet = new DatagramPacket(receiveBuf, receiveBuf.length);
			
			//	Thread termination in this code is primitive.  When shutdown() is called (by the
			//	application's main thread, so asynchronously to the threads just mentioned) it
			//	closes the sockets.  This causes an exception on any thread trying to read from
			//	it, which is what provokes thread termination.
			try {
				while ( !mAmShutdown ) {
					try {
						mDatagramSocket.receive(packet);
						if ( packet.getLength() < HEADER_LEN )
							throw new Exception("Bad header: length = " + packet.getLength());
						String headerStr = new String( receiveBuf, 0, HEADER_LEN );
						if ( ! headerStr.equalsIgnoreCase(HEADER_STR) )
							throw new Exception("Bad header: got '" + headerStr + "', wanted '" + HEADER_STR + "'");

						// The full amount of data to be sent.
						byte[] data = new byte[XFERSIZE[port - mBasePort]];
						
						// Split it up into 1000 byte increments.
						int bytesSent = 0;
						while (bytesSent < data.length) {
							byte[] part = new byte[RESPONSE_OKAY_LEN + Math.min(data.length - bytesSent, 1000)];
							System.arraycopy(RESPONSE_OKAY_BYTES, 0, part, 0, RESPONSE_OKAY_LEN);
							System.arraycopy(data, bytesSent, part, RESPONSE_OKAY_LEN, part.length - RESPONSE_OKAY_LEN);
							DatagramPacket sendpacket = new DatagramPacket(part, part.length, packet.getAddress(), packet.getPort());
							mDatagramSocket.send( sendpacket);
							bytesSent += part.length - RESPONSE_OKAY_LEN;
						}
					} catch (SocketTimeoutException e) {
						// socket timeout is normal
					} catch (Exception e) {
						Log.w(TAG,  "Dgram reading thread caught " + e.getClass().getName() + " exception: " + e.getMessage());
					}
				}
			} finally {
				if ( mDatagramSocket != null ) { mDatagramSocket.close(); mDatagramSocket = null; }
			}

		}
	}

	private class TcpThread extends Thread {
		int port;
		private ServerSocket mServerSocket;

		TcpThread(int portnum) throws Exception{
			this.port = portnum;

			String serverIP = IPFinder.localIP();
			if ( serverIP == null ) throw new Exception("IPFinder isn't providing the local IP address.  Can't run.");
			this.mServerSocket = new ServerSocket();
			this.mServerSocket.bind(new InetSocketAddress(serverIP, port));
			this.mServerSocket.setSoTimeout(NetBase.theNetBase().config().getAsInt("net.timeout.granularity", 500));

			Log.i(TAG,  "Server socket = " + mServerSocket.getLocalSocketAddress());
		}

		public void run() {
			byte[] header = new byte[HEADER_LEN];
			byte[] data = new byte[XFERSIZE[port - mBasePort]];
			int socketTimeout = NetBase.theNetBase().config().getAsInt("net.timeout.socket", 5000);
			try {
				while ( !isShutdown() ) {
					Socket sock = null;
					try {
						// accept() blocks until a client connects.  When it does, a new socket is created that communicates only
						// with that client.  That socket is returned.
						sock = mServerSocket.accept();
						// We're going to read from sock, to get the message to echo, but we can't risk a client mistake
						// blocking us forever.  So, arrange for the socket to give up if no data arrives for a while.
						sock.setSoTimeout(socketTimeout);
						InputStream is = sock.getInputStream();
						OutputStream os = sock.getOutputStream();
						// Read the header.  Either it gets here in one chunk or we ignore it.  (That's not exactly the
						// spec, admittedly.)
						int len = is.read(header);
						if ( len != HEADER_LEN )
							throw new Exception("Bad header length: got " + len + " but wanted " + HEADER_LEN);
						String headerStr = new String(header); 
						if ( !headerStr.equalsIgnoreCase(HEADER_STR) )
							throw new Exception("Bad header: got '" + headerStr + "' but wanted '" + HEADER_STR + "'");
						
						// Write response header.
						os.write(RESPONSE_OKAY_BYTES);

						// Write the data in one fell swoop. ("Split it up" to be extendible to big files).
						int bytesSent = 0;
						while (bytesSent < data.length) {
							int partLen = Math.min(data.length - bytesSent, 1000000);
							os.write(data, bytesSent, partLen);
							bytesSent += partLen;
						}
					} catch (SocketTimeoutException e) {
						// normal behavior, but we're done with the client we were talking with
					} catch (Exception e) {
						Log.i(TAG, "TCP thread caught " + e.getClass().getName() + " exception: " + e.getMessage());
					} finally {
						if ( sock != null ) try { sock.close(); sock = null;} catch (Exception e) {}
					}
				}
			} catch (Exception e) {
				Log.w(TAG, "TCP server thread exiting due to exception: " + e.getMessage());
			} finally {
				if ( mServerSocket != null ) try { mServerSocket.close(); mServerSocket = null; } catch (Exception e) {}
			}
		}

	}
}


