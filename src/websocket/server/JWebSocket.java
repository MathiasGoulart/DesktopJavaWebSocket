package websocket;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.bind.DatatypeConverter;

/**
 * This is a simple implementation of RFC6455 - WebSockets.
 * The main goal is provide a class that communicate with browsers, with no need to run it
 * in a App Server/Container.
 * This is abstract because it can identify a new WS connection, send and receive image as server,
 * but you can implement your own subprotocol. To do that, follow the following steps:
 * 
 * 1 - Extend you class to JWebSocket
 * 
 * 2 - Implement interpretClientTextMessage and interpretClientBinaryMessage (others methods are optional)
 * 
 * 3 - Send client messages by using sendTextMessage and send_binary_message_to_client
 * 
 * @author: Mathias de Souza Goulart
 * @date: 2019-04-16 - First version
 * @version: 0.1 - Just a part of the protocol is implemented
 *
 */
public abstract class JWebSocket {
	
	public static final String CONTINUOUS_MSG_OPCODE = "0000", TEXT_OPCODE = "0001",
			BINARY_OPCODE = "0010", PING_OPCODE = "1001", PONG_OPCODE = "1010",
			CLOSE_CONNECTION_OPCODE = "1000";
	protected static final int PING_NOT_SENT_TOLERANCE = 8;
	
	private byte[] pingContent;
	private int pingNotSent, pingPckgSize;
	private long beginPingCount, pingInterval;
	private boolean keepPinging;
	
	private Socket client;
	private ServerSocket server;

	protected abstract void onServerStarted(ServerSocket server);

	protected abstract void clientConnected(Socket client);

	protected abstract void clientDisconnected();

	protected abstract void interpretClientTextMessage(String decoded_data);

	protected abstract void interpretClientBinaryMessage(byte[] decoded_data);
	
	public JWebSocket() {
		this.keepPinging = true;
		this.pingInterval = 5000;
		this.pingPckgSize = 8;
		this.beginPingCount = System.currentTimeMillis();
		this.pingNotSent = 0;
	}
	
	public JWebSocket(boolean keepPinging) {
		this.keepPinging = keepPinging;
		this.pingInterval = 5000;
		this.pingPckgSize = 8;
		this.beginPingCount = System.currentTimeMillis();
		this.pingNotSent = 0;
	}
	
	public JWebSocket(boolean keepPinging, long pingInterval) {
		this.keepPinging = keepPinging;
		this.pingInterval = pingInterval;
		this.pingPckgSize = 8;
		this.beginPingCount = System.currentTimeMillis();
		this.pingNotSent = 0;
	}
	
	public JWebSocket(boolean keepPinging, long pingInterval, int packageSize) {
		this.keepPinging = keepPinging;
		this.pingInterval = pingInterval;
		this.pingPckgSize = packageSize;
		this.beginPingCount = System.currentTimeMillis();
		this.pingNotSent = 0;
	}
	
	protected Socket getClient() {
		return this.client;
	}

	protected void sendTextMessage(String text_answer) {
		if (client == null) {
			// TODO: return some kind of error
			return;
		}

		if (!client.isConnected()) {
			// TODO: report client isn't connected anymore error
			return;
		}

		try {
			byte[] text_answer_bytes = text_answer.getBytes("UTF-8");
			byte[] response_frame = createResponseFrame(text_answer_bytes, TEXT_OPCODE);
			OutputStream out = client.getOutputStream();

			out.write(response_frame);
		} catch (IOException ex) {
			// TODO: report writing error
			ex.printStackTrace();
		}
	}

	protected void sendBinaryMessage(byte[] binary_answer_bytes) {
		if (client == null) {
			// TODO: return some kind of error
			return;
		}

		if (!client.isConnected()) {
			// TODO: report client isn't connected anymore error
			return;
		}

		try {
			byte[] response_frame = createResponseFrame(binary_answer_bytes, BINARY_OPCODE);
			OutputStream out = client.getOutputStream();

			out.write(response_frame);
		} catch (IOException ex) {
			// TODO: Report writing error
			ex.printStackTrace();
		}
	}
	
	/**
	 * Here you can send a message and specify the type of message for your self.
	 * For more details see RFC 6455:
	 * Opcode: The following values are defined.

      *  %x0 denotes a continuation frame

      *  %x1 denotes a text frame

      *  %x2 denotes a binary frame

      *  %x3-7 are reserved for further non-control frames

      *  %x8 denotes a connection close

      *  %x9 denotes a ping

      *  %xA denotes a pong

      *  %xB-F are reserved for further control frames
	 * @param message_data - the bytes of the message to be sent
	 * @param opcode - the type of message that is being sent
	 * @throws IOException - May we get a error when trying to send a message to client
	 */
	protected void sendMessage(byte[] message_data, String opcode) throws IOException {
		if (client == null) {
			// TODO: return some kind of error
			return;
		}

		if (!client.isConnected()) {
			// TODO: report client isn't connected anymore error
			return;
		}

		try {
			byte[] response_frame = createResponseFrame(message_data, opcode);
			OutputStream out = client.getOutputStream();

			out.write(response_frame);
		} catch (IOException ex) {
			// TODO: report writing error
			ex.printStackTrace();
		}
	}
	
	/**
	 * Start a new WebSocket server and list the given port.
	 * It will keep alive until it receive a CLOSE_CONNECTION opcode, or
	 * some error happen.
	 * @param port
	 */
	public void start(int port) {
		try {
			this.server = new ServerSocket(port);

			this.onServerStarted(this.server);
			this.client = this.server.accept();

			if (!this.handshake()) {
				this.stop();
				return;
			}

			clientConnected(client);

			InputStream in = this.client.getInputStream();
			OutputStream out;

			while (!this.client.isClosed()) {
				in = this.client.getInputStream();
				if (in.available() == 0) {
					if (this.shouldPing()) {
						this.pingClient();
					} else {
						continue;						
					}
				}
				BufferedInputStream bis = new BufferedInputStream(in);

				int reader_size = in.available();
				byte[] read = new byte[reader_size];
				if (bis.read(read) == -1) {
					continue;
				}

				byte bframe_bits = read[0];
				String sframe_bits = get_binary(bframe_bits);
				if (sframe_bits.length() == 32)
					sframe_bits = sframe_bits.substring(24);
				/*
				 * First byte:
				 * 
				 * FIN: 1 bit
				 * 
				 * Indicates that this is the final fragment in a message. The first fragment
				 * MAY also be the final fragment.
				 * 
				 * RSV1, RSV2, RSV3: 1 bit each
				 * 
				 * MUST be 0 unless an extension is negotiated that defines meanings for
				 * non-zero values. If a nonzero value is received and none of the negotiated
				 * extensions defines the meaning of such a nonzero value, the receiving
				 * endpoint MUST _Fail the WebSocket Connection_.
				 */
				boolean bFin = sframe_bits.startsWith("1"), bRsv1 = sframe_bits.charAt(1) == '0',
						bRsv2 = sframe_bits.charAt(2) == '0', bRsv3 = sframe_bits.charAt(3) == '0';

				byte[] decoded_data = null;
				if (bFin) {
					decoded_data = readData(read);
				} else {
					// TODO: implement a continuous message (when bFin = False)
				}
				/*
				 * 
				 * 
				 * Opcode: 4 bits
				 * 
				 * Defines the interpretation of the "Payload data". If an unknown opcode is
				 * received, the receiving endpoint MUST _Fail the WebSocket Connection_. The
				 * following values are defined.
				 * 
				 * %x0 denotes a continuation frame
				 * 
				 * %x1 denotes a text frame
				 * 
				 * %x2 denotes a binary frame
				 * 
				 * %x3-7 are reserved for further non-control frames
				 * 
				 * %x8 denotes a connection close
				 * 
				 * %x9 denotes a ping
				 * 
				 * %xA denotes a pong
				 * 
				 * %xB-F are reserved for further control frames
				 */
				String opcode_bits = sframe_bits.substring(4);

				switch (opcode_bits) {
				case CONTINUOUS_MSG_OPCODE:
					break;
				case TEXT_OPCODE:
					String text_decoded_data = new String(decoded_data);

					// subprotocol
					interpretClientTextMessage(text_decoded_data);

					break;
				case BINARY_OPCODE:

					// subprotocol
					interpretClientBinaryMessage(decoded_data);

					break;
				case CLOSE_CONNECTION_OPCODE:
					this.stop();
					break;
				case PING_OPCODE:
					this.sendMessage(decoded_data, PONG_OPCODE);
					break;
				case PONG_OPCODE:
					if (this.pingContent != null) {
						String sPingContent = new String(this.pingContent, "UTF-8");
						String sPongContent = new String(decoded_data, "UTF-8");
						if(!sPingContent.equals(sPongContent)) {
							if (this.pingNotSent < this.PING_NOT_SENT_TOLERANCE) {
								this.pingNotSent++;
							} else {
								this.stop();
							}
						}
					}
					break;
				}

			}

			clientDisconnected();

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			this.stop();
		}
	}
	
	public boolean sendStopSignal() throws Exception {
		if (getClient() != null ? !getClient().isConnected() : false)
			throw new Exception("Client is not connected yet!");

		if (getClient() != null ? getClient().isClosed() : false)
			throw new Exception("Trying to close connection with client when it is already closed!");
		
		// indicates that an endpoint is "going away", such as a server
	    //  going down or a browser having navigated away from a page.
		this.sendMessage("1001".getBytes("UTF-8"), CLOSE_CONNECTION_OPCODE);

		this.client.close();
		this.server.close();
		return true;
	}
	
	protected void stop() {
		try {
			this.client.close();
			this.server.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private String randomBitMask(int leng) {
		double rand = 0;
		String bit;

		StringBuilder sb = new StringBuilder();

		for (int i = 0; i < leng; i++) {
			rand = Math.random();
			if (rand < 0.5)
				bit = "0";
			else
				bit = "1";
			sb.append(bit);
		}

		return sb.toString();
	}

	private boolean handshake() {
		try {
			InputStream in = client.getInputStream();
			BufferedInputStream bis = new BufferedInputStream(in);
			byte[] read = new byte[in.available()];

			if (bis.read(read) == -1) {
				return false;
			}

			String data = new String(read, "UTF-8");
			Matcher get_matcher = Pattern.compile("^GET").matcher(data);
			if (get_matcher.find()) {
				Matcher key_matcher = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(data);
				key_matcher.find();
				byte[] response = ("HTTP/1.1 101 Switching Protocols\r\n" + "Connection: Upgrade\r\n"
						+ "Upgrade: websocket\r\n" + "Sec-WebSocket-Accept: "
						+ DatatypeConverter.printBase64Binary(MessageDigest.getInstance("SHA-1").digest(
								(key_matcher.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")))
						+ "\r\n\r\n").getBytes("UTF-8");
				OutputStream out = client.getOutputStream();
				out.write(response, 0, response.length);
				return true;
			} else {
				return false;
			}
		} catch (IOException e1) {
			System.out.println("Error when trying to read handshake data!!!");
			return false;
		} catch (NoSuchAlgorithmException e) {
			System.out.println("SHA-1 hasn't been found to digest message!!!");
			return false;
		}

	}

	private String get_binary(int arg0) {
		String bits = Integer.toBinaryString(arg0);
		int size = bits.length();
		if (size > 8) {
			bits = bits.substring(size - 8, size);
		}
		return bits;
	}

	private byte[] createResponseFrame(byte[] data_bytes, String opcode) {
		try {

			boolean use_mask = false; // server MUST NOT mask its frames

			String frame_header = "";

			String fin = "1", rsv1 = "0", rsv2 = "0", rsv3 = "0";

			frame_header = frame_header.concat(fin).concat(rsv1).concat(rsv2).concat(rsv3).concat(opcode);

			if (use_mask)
				frame_header = frame_header.concat("1");
			else
				frame_header = frame_header.concat("0");

			int data_size = data_bytes.length, padding_zeros;
			if (data_size <= 125) {
				padding_zeros = 7;
			} else if (data_size <= 65536) {
				padding_zeros = 16;
				frame_header = frame_header.concat("1111110"); // 126 - Indicates the client the he needs to read the
																// next 16 bits
			} else {
				padding_zeros = 64;
				frame_header = frame_header.concat("1111111"); // 127 - Indicates the client the he needs to read the
																// next 64 bits
			}

			String leng_bits = Integer.toBinaryString(data_size);

			for (int i = leng_bits.length(); i < padding_zeros; i++) {
				leng_bits = "0".concat(leng_bits);
			}
			frame_header = frame_header.concat(leng_bits);

			byte[] mask_bytes = null;
			if (use_mask) {
				int mask_size = 32;
				String mask = randomBitMask(mask_size);
				frame_header = frame_header.concat(mask);
				mask_bytes = new byte[mask_size / 8];
				for (int i = 0; i < mask_size; i += 8) {
					mask_bytes[i / 8] = (byte) Integer.parseUnsignedInt(mask.substring(i, i + 8), 2);
				}
			}

			byte[] bframe_header = new byte[frame_header.length() / 8];
			for (int i = 0; i < frame_header.length(); i += 8) {
				bframe_header[i / 8] = (byte) Integer.parseUnsignedInt(frame_header.substring(i, i + 8), 2);
			}

			byte[] retorno = new byte[bframe_header.length + data_bytes.length];
			for (int i = 0; i < bframe_header.length; i++) {
				retorno[i] = bframe_header[i];
			}

			for (int i = 0; i < data_bytes.length; i++) {
				retorno[i + bframe_header.length] = use_mask ? (byte) (data_bytes[i] ^ mask_bytes[i & 0x3])
						: data_bytes[i];
			}

			return retorno;
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	private byte[] readData(byte[] data) {

		/*
		 * If the second byte minus 128 is between 0 and 125, this is the length of the
		 * message. If it is 126, the following 2 bytes (16-bit unsigned integer), if
		 * 127, the following 8 bytes (64-bit unsigned integer, the most significant bit
		 * MUST be 0) are the length.
		 */

		int size = Integer.parseUnsignedInt(get_binary(data[1]), 2) - 128;
		int start_key = 2;
		if (size <= 125) {
			start_key = 2;
		} else if (size >= 126) {
			StringBuilder leng_bits = new StringBuilder();
			int read_next_bytes = 2;
			start_key = 4;

			if (size == 127) {
				read_next_bytes = 8;
				start_key = 10;
			}

			for (int i = 0; i < read_next_bytes; i++) {
				leng_bits.append(get_binary(data[i + 2]));
			}
			size = Integer.parseUnsignedInt(leng_bits.toString(), 2);
		}

		// read message key
		byte[] bkey = new byte[] { data[start_key], data[start_key + 1], data[start_key + 2], data[start_key + 3] };
		start_key += bkey.length; // update this information to use when reading the data bytes inside incoming
									// data

		byte[] bdata = new byte[size];
		for (int i = 0; i < size; i++) {
			bdata[i] = data[i + start_key];
		}

		byte[] decoded = new byte[size];
		for (int i = 0; i < bdata.length; i++) {
			decoded[i] = (byte) (bdata[i] ^ bkey[i & 0x3]);
		}

		return decoded;
	}
	
	public void setKeepPinging(boolean keepPinging) {
		this.keepPinging = keepPinging;
	}
	
	public void pingClient() throws Exception {
		if (pingContent != null) {
			throw new Exception("Ping without answer still waiting");
		}
		
		byte[] pingPckg = this.getPingPackage(this.pingPckgSize);
		
		// send the ping frame
		this.sendMessage(pingPckg, PING_OPCODE);
		
		// keep the ping information to prevent a new ping
		this.beginPingCount = System.currentTimeMillis();
		
		// keep the ping content to validate later
		this.pingContent = pingPckg;
	}
	
	private boolean shouldPing() {
		return this.keepPinging ? 
				System.currentTimeMillis() - this.beginPingCount >= this.pingInterval : 
					false;
	}
	
	private byte randomByte() {
		String oct = "";
		for(int i = 0; i < 8; i++) {
			String bit = (Math.random() * 10) > 5 ? "1" : "0";
			oct = oct.concat(bit);
		}
		return Byte.parseByte(oct);
	}
	
	protected byte[] getPingPackage(int size) {
		// maximum ping size is 125
		size = size > 125 ? 125 : size;
		
		// generate random content for ping
		byte[] ping_bytes = new byte[size];
		for(int i = 0; i < size; i++) {
			ping_bytes[i] = randomByte();
		}
		
		return ping_bytes;
	}

}
