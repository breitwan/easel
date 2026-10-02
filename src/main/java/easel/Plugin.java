package easel;

import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Plugin extends JavaPlugin {

    private Eval eval;
    private Server server;

    @Override
    public void onEnable() {
        try {
            Class.forName("jdk.jshell.JShell");
        } catch (ClassNotFoundException e) {
            getLogger().severe("easel needs a JDK with jdk.jshell, not a JRE");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            eval = Eval.open(getClass().getClassLoader(), getFile());
            server = new Server(new InetSocketAddress(Server.HOST, Server.PORT), this::evalOnMain, getLogger());
        } catch (Exception e) {
            getLogger().severe("easel failed to bind " + Server.HOST + ":" + Server.PORT + ": " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("easel MCP http://" + Server.HOST + ":" + Server.PORT + "/mcp");
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (eval != null) {
            eval.close();
            eval = null;
        }
    }

    private Eval.Result evalOnMain(String code) throws Exception {
        if (eval == null) {
            throw new IllegalStateException("JShell closed");
        }
        if (getServer().isPrimaryThread()) {
            return eval.eval(code);
        }
        try {
            return getServer().getScheduler()
                    .callSyncMethod(this, () -> eval.eval(code))
                    .get(Server.EVAL_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return Eval.Result.timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Eval.Result.error(e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            if (c instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
