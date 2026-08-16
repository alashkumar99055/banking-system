public class Main {
    public static void main(String[] args) throws Exception {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort.trim());
        } else if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        Server server = new Server(port);
        int actualPort = server.start();
        System.out.println("Banking system server started on http://0.0.0.0:" + actualPort);
    }
}
