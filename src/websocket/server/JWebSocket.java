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

public abstract class JWebSocket {
	
	private Socket client;
	private ServerSocket server;
	
	protected abstract void onServerStarted(ServerSocket server);
	
	protected abstract void client_connected();
	
	protected abstract void client_disconnected();
	
	protected abstract void interpret_client_text_message(String decoded_data);

	protected abstract void interpret_client_binary_message(byte[] decoded_data);
	
	protected Socket getClient() {
		return this.client;
	}
	
	protected void send_text_message_to_client(String text_answer) {
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
			byte[] response_frame = create_response_frame(text_answer_bytes);
			OutputStream out = client.getOutputStream();

			out.write(response_frame);
		} catch (IOException ex) {
			// TODO: report writing error
			ex.printStackTrace();
		}
	}

	protected void send_binary_message_to_client(byte[] binary_answer_bytes) {
		if (client == null) {
			// TODO: return some kind of error
			return;
		}

		if (!client.isConnected()) {
			// TODO: report client isn't connected anymore error
			return;
		}

		try {
			byte[] response_frame = create_response_frame(binary_answer_bytes);
			OutputStream out = client.getOutputStream();

			out.write(response_frame);
		} catch (IOException ex) {
			// TODO: Report writing error
			ex.printStackTrace();
		}
	}

	public void start() {
		try {
			int port = 32152;
			this.server = new ServerSocket(port);

			this.onServerStarted(this.server);
			this.client = this.server.accept();

			this.handshake();

			client_connected();
			
			InputStream in = this.client.getInputStream();
			OutputStream out;
			
			while (!this.client.isClosed()) {
				in = this.client.getInputStream();
				if (in.available() == 0)
					continue;
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
					decoded_data = read_data(read);
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
				byte[] response_frame;
				switch (opcode_bits) {
				case "0001": // text message
					String text_decoded_data = new String(decoded_data);

					// internal protocol
					interpret_client_text_message(text_decoded_data);

					break;
				case "0010": // binary message

					// internal protocol
					interpret_client_binary_message(decoded_data);

					break;
				case "1000": // close connection
					this.stop();
					break;
				case "1001": // ping
					byte pong_byte = (byte) Integer.parseUnsignedInt("10001010");
					out = this.client.getOutputStream();
					out.write(pong_byte);
					break;
				}

			}

			client_disconnected();

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			this.stop();
		}
	}
	
	public void stop() {
		try {
			this.client.close();
			this.server.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	} 
	
	private String random_bit_mask(int leng) {
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
						+ DatatypeConverter.printBase64Binary(MessageDigest.getInstance("SHA-1")
								.digest((key_matcher.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")))
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

	private byte[] create_response_frame(byte[] data_bytes) {
		try {

			boolean use_mask = true;

			String frame_header = "";

			String fin = "1", rsv1 = "0", rsv2 = "0", rsv3 = "0", text_opcode = "0001";

			frame_header = frame_header.concat(fin).concat(rsv1).concat(rsv2).concat(rsv3).concat(text_opcode);

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
				String mask = random_bit_mask(mask_size);
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

	private byte[] read_data(byte[] data) {

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

	
}
