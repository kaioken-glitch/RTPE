package rtpe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class RtpeServer {

    private final EventLog log = new EventLog();
    private final SandboxLedger ledger = new SandboxLedger(log);
    private final TransactionEngine engine = new TransactionEngine(ledger::execute, log);

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("RTPE_PORT", "8080"));
        new RtpeServer().start(port);
    }

    private void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/api/state", ex -> route(ex, "GET", this::state));
        server.createContext("/api/transactions", ex -> route(ex, "POST", this::submit));
        server.createContext("/api/reset", ex -> route(ex, "POST", this::reset));
        server.start();
        log.log("INFO", "RTPE listening on :%d", port);
    }

    private interface Action {
        String run(Map<String, String> q);
    }

    private void route(HttpExchange ex, String method, Action action) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        try {
            if ("OPTIONS".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!method.equals(ex.getRequestMethod())) {
                respond(ex, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            respond(ex, 200, action.run(query(ex.getRequestURI().getRawQuery())));
        } catch (IllegalArgumentException e) {
            respond(ex, 400, "{\"error\":" + str(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            log.log("ERROR", "request failed: %s", e);
            respond(ex, 500, "{\"error\":\"internal error\"}");
        } finally {
            ex.close();
        }
    }

    private String submit(Map<String, String> q) {
        TransactionLabel label = TransactionLabel.valueOf(q.getOrDefault("label", "").toUpperCase());
        Transaction tx = new Transaction(
                q.getOrDefault("id", ""), label,
                Long.parseLong(q.getOrDefault("amount", "0")),
                Boolean.parseBoolean(q.getOrDefault("fail", "false")));
        TransactionEngine.SubmitOutcome outcome = engine.submit(tx);
        return "{\"outcome\":\"" + outcome + "\",\"requestNo\":" + tx.requestNo()
                + ",\"windowMs\":" + label.windowMs() + "}";
    }

    private String reset(Map<String, String> q) {
        engine.reset();
        ledger.reset();
        log.clear();
        log.log("INFO", "sandbox reset");
        return "{\"ok\":true}";
    }

    private String state(Map<String, String> q) {
        long since = Long.parseLong(q.getOrDefault("since", "0"));
        TransactionEngine.Snapshot snap = engine.snapshot();
        TransactionEngine.Stats s = engine.stats();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"serverTime\":").append(System.currentTimeMillis());
        sb.append(",\"wallet\":{\"balanceCents\":").append(ledger.balanceCents())
          .append(",\"initialCents\":").append(SandboxLedger.INITIAL_BALANCE_CENTS)
          .append(",\"lockedUnits\":").append(ledger.lockedUnits())
          .append(",\"stockUnits\":").append(SandboxLedger.STOCK_UNITS)
          .append(",\"debits\":").append(ledger.debits())
          .append(",\"credits\":").append(ledger.credits())
          .append(",\"locks\":").append(ledger.locks()).append('}');
        sb.append(",\"stats\":{\"submitted\":").append(s.submitted.sum())
          .append(",\"accepted\":").append(s.accepted.sum())
          .append(",\"absorbed\":").append(s.absorbed.sum())
          .append(",\"succeeded\":").append(s.succeeded.sum())
          .append(",\"failed\":").append(s.failed.sum())
          .append(",\"recovered\":").append(s.recovered.sum())
          .append(",\"discarded\":").append(s.discarded.sum()).append('}');
        sb.append(",\"maps\":{\"active\":").append(snap.active().size())
          .append(",\"absorbedKeys\":").append(snap.absorbedKeys())
          .append(",\"absorbedItems\":").append(snap.absorbedItems()).append('}');
        sb.append(",\"active\":[");
        List<TransactionEngine.ActiveView> active = snap.active();
        for (int i = 0; i < active.size(); i++) {
            TransactionEngine.ActiveView v = active.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(str(v.id())).append(",\"label\":").append(str(v.label()))
              .append(",\"windowMs\":").append(v.windowMs()).append(",\"remainingMs\":").append(v.remainingMs())
              .append(",\"absorbed\":").append(v.absorbed()).append(",\"attempt\":").append(v.attempt())
              .append(",\"requestNo\":").append(v.requestNo()).append('}');
        }
        sb.append("],\"logs\":[");
        boolean first = true;
        for (EventLog.Entry e : log.since(since)) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"seq\":").append(e.seq()).append(",\"ts\":").append(e.ts())
              .append(",\"level\":").append(str(e.level())).append(",\"msg\":").append(str(e.message())).append('}');
        }
        sb.append("],\"lastSeq\":").append(log.lastSeq()).append('}');
        return sb.toString();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static String str(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
