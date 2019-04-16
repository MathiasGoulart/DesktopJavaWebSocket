<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
    pageEncoding="ISO-8859-1"%>
<!DOCTYPE html>
<html>
<head>
<meta charset="ISO-8859-1">
<title>WebSocket test</title>
</head>
<body>
	
	<p id="status-socket">Welcome to websocket chat</p>
	<input id="mensagem" type="text">
	<button id="SendMsg">Send</button>
	<button id="CloseCon">Close</button>
	
	<script src="js/jquery.min.js"></script>
	<script type="text/javascript">
		$(document).ready(function(){
			var dom_status = $("#status-socket");
			
			var connection = new WebSocket('ws://127.0.0.1:32115');

			connection.onopen = function () {
			  	dom_status.text("Conectado ao servidor!");
			};
			
			connection.onclose = function(event) {
				dom_status.append("<br>Disconnected!");
				console.log(event);
			};
			
			connection.onmessage = function(msg) {
				dom_status.append("<br>Server: "+msg.data);
			}
			
			connection.onerror = function(msg) {
				dom_status.append("<br>Error"+msg.data);
			};
			
			$("#SendMsg").click(function(){
				var msg = $("#mensagem").val();
				connection.send(msg);
				dom_status.append("<br>You: "+msg);
			});
			
			$("#CloseCon").click(function(){
				connection.close();
			});
			
		});
				
	</script>
</body>
</html>