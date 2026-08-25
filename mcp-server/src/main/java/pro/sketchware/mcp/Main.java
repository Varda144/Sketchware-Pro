package pro.sketchware.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** CLI entry point for the Sketchware Pro MCP server. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path workspacePath = Paths.get(System.getProperty("user.dir"));
        String host = "127.0.0.1";
        int port = 8080;
        Workspace.Mode mode = Workspace.Mode.READ_ONLY;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--workspace" -> workspacePath = Paths.get(next(args, ++i));
                case "--host" -> host = next(args, ++i);
                case "--port" -> port = Integer.parseInt(next(args, ++i));
                case "--allow-write" -> mode = Workspace.Mode.WRITE;
                case "--allow-destructive" -> mode = Workspace.Mode.DESTRUCTIVE;
                case "--help", "-h" -> {
                    usage();
                    return;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }

        if ("0.0.0.0".equals(host) && (System.getenv("MCP_TOKEN") == null
                || System.getenv("MCP_TOKEN").isBlank())) {
            System.err.println("Refusing to bind non-loopback without MCP_TOKEN set.");
            System.exit(2);
        }

        Workspace workspace = new Workspace(workspacePath, mode);
        McpServer server = new McpServer(workspace, host, port);
        server.start();
        System.out.println("Sketchware Pro MCP server");
        System.out.println("  workspace : " + workspace.root());
        System.out.println("  mode      : " + workspace.mode());
        System.out.println("  endpoint  : http://" + host + ":" + server.port() + "/mcp");
        if (System.getenv("GEMINI_API_KEY") != null) {
            System.out.println("  gemini    : configured via GEMINI_API_KEY");
        }
    }

    private static String next(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value after " + args[i - 1]);
        }
        return args[i];
    }

    private static void usage() {
        System.out.println("""
                Usage: java -jar mcp-server.jar [options]
                  --workspace <dir>     Sketchware Pro project/workspace directory
                                        (.sketchware root or any parent of it)
                  --host <host>         bind address, default 127.0.0.1
                  --port <port>         default 8080
                  --allow-write         enable mutating tools (atomic writes + backups)
                  --allow-destructive   additionally enable delete/restore tools

                Environment:
                  GEMINI_API_KEY        enables generate_block_code tool
                  MCP_TOKEN             bearer token; required when binding non-loopback""");
    }
}
