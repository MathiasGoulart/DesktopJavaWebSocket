package websocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

public class ChatWebSocket extends JWebSocket {

	@Override
	protected void onServerStarted(ServerSocket server) {
		System.out.println("Server started on port: "+String.valueOf(server.getLocalPort()));
	}

	@Override
	protected void client_connected(Socket client) {
		System.out.println("Client connected and using websocket IP "+client.getLocalAddress().getHostAddress());
	}

	@Override
	protected void client_disconnected() {
		// TODO Auto-generated method stub

	}

	@Override
	protected void interpret_client_text_message(String decoded_data) {
		System.out.println("Client: "+decoded_data);
		InputStreamReader isr = new InputStreamReader(System.in);
		BufferedReader br = new BufferedReader(isr);
		try {
			String read_data = br.readLine();
			this.send_text_message_to_client(read_data);
		} catch (IOException e) {
			System.out.println("Error when trying to read data");
		}
	}

	@Override
	protected void interpret_client_binary_message(byte[] decoded_data) {
		// TODO Auto-generated method stub

	}

	public static void main(String[] args) {
		ChatWebSocket server = new ChatWebSocket();
		server.start(32115);
	}

}
