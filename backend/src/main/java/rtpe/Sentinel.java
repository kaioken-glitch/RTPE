package rtpe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * SENTINEL: attack signalling with a sandbox test window.
 *
 * Every exchange is CARGO: it gets an id, the sender's IP label, a device label and a batch window.
 * Batches close on a timer and the cargo is screened. Borderline cargo is IRREGULAR and gets RECYCLED
 * (re-screened with deeper normalisation) before anything is blocked. Confirmed attacks are held for
 * SINK_DELAY_MS, then SUNK: the raw payload is swallowed, stripped to a signature and filed as a POI.
 * A second strike from the same IP (in a different window) blacklists the IP and device.
 *
 * Payloads are only ever inspected as inert text. Nothing is executed, stored raw, or echoed back.
 *
 * Wired into RtpeServer: Sentinel.install(server, log) and Sentinel.gate(ex) on live routes.
 */
public final class Sentinel {

    private Sentinel() {}

    // ---------- config (env overridable) ----------
    static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
    static final long    BATCH_MS    = Long.parseLong(env("BATCH_MS", "2000"));
    static final long    RECYCLE_MS  = Long.parseLong(env("RECYCLE_MS", "3000"));
    static final long    SINK_MS     = Long.parseLong(env("SINK_DELAY_MS", "10000"));
    static final int     ATTACK_AT   = Integer.parseInt(env("ATTACK_SCORE", "5"));
    static final int     BURST_SOFT  = Integer.parseInt(env("BURST_LIMIT", "20"));   // per IP per window -> flagged
    static final int     BURST_HARD  = Integer.parseInt(env("HARD_LIMIT", "100"));   // per IP per window -> 429
    static final int     MAX_BODY    = Integer.parseInt(env("MAX_BODY", "16384"));
    static final int     MAX_LIVE    = Integer.parseInt(env("MAX_LIVE", "5000"));
    static final long    BL_HOURS    = Long.parseLong(env("BLACKLIST_HOURS", "24"));  // 0 = permanent
    static final boolean TRUST_PROXY = Boolean.parseBoolean(env("TRUST_PROXY", System.getenv("RENDER") != null ? "true" : "false"));  // Render sits behind a proxy
    static final boolean SANDBOX_STRIKES = Boolean.parseBoolean(env("SANDBOX_COUNTS_TOWARD_BLACKLIST", "false"));
    static final String  ADMIN_TOKEN = env("ADMIN_TOKEN", "");
    static final String  ALLOW_ORIGIN = env("ALLOW_ORIGIN", "*");
    static volatile EventLog LOG;
    static final Path    STORE       = Path.of(env("POI_FILE", "poi.tsv"));

    // ---------- model ----------
    static final class Cargo {
        final String id, ip, fp, device, method, target, batchId, user, who;
        final boolean sandbox, oversize;
        final long ts = System.currentTimeMillis();
        volatile String body;                       // dropped when swallowed
        volatile String state = "QUEUED";
        volatile int score, recycles;
        volatile long sinkAt, doneAt;
        final List<String> labels = new CopyOnWriteArrayList<>();
        Cargo(String id, String ip, String fp, String device, String method, String target, String body, String batchId, boolean sandbox, boolean oversize, String user, String who) {
            this.id = id; this.ip = ip; this.fp = fp; this.device = device; this.method = method;
            this.target = target; this.body = body; this.batchId = batchId; this.sandbox = sandbox; this.oversize = oversize;
            this.user = user; this.who = who;
        }
        String inspectText() { return method + " " + target + "\n" + (body == null ? "" : body); }
    }

    static final class Batch {
        final String id; final long openedAt = System.currentTimeMillis(); long closedAt;
        final List<Cargo> cargo = new CopyOnWriteArrayList<>();
        final Map<String, Integer> ipLog = new ConcurrentHashMap<>();
        Batch(String id) { this.id = id; }
    }

    static final class Poi {
        final String ip; long firstSeen, lastSeen; int strikes;
        final Set<String> classes = new TreeSet<>(), sigs = new TreeSet<>(), devices = new TreeSet<>(), fps = new TreeSet<>(), batches = new TreeSet<>();
        final Deque<String> samples = new ArrayDeque<>();
        Poi(String ip, long t) { this.ip = ip; this.firstSeen = this.lastSeen = t; }
    }

    record Rule(String cls, int weight, Pattern p) {}
    record Scan(int score, Set<String> labels) {
        Scan plus(int w, String label) { Set<String> l = new LinkedHashSet<>(labels); l.add(label); return new Scan(score + w, l); }
    }

    // ---------- state ----------
    static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(2, r -> { Thread t = new Thread(r, "sentinel"); t.setDaemon(true); return t; });
    static final Map<String, Cargo> LIVE = new ConcurrentHashMap<>();
    static final Map<String, Poi> POIS = new ConcurrentHashMap<>();
    static final Map<String, Long> BL_IP = new ConcurrentHashMap<>(), BL_DEVICE = new ConcurrentHashMap<>();
    static final Map<String, Deque<Long>> FLAGS = new ConcurrentHashMap<>();
    static final Deque<Batch> CLOSED = new ConcurrentLinkedDeque<>();
    static final Deque<String> EVENTS = new ConcurrentLinkedDeque<>();
    static final AtomicLong SEQ = new AtomicLong(), BATCH_SEQ = new AtomicLong(), BLOCKED = new AtomicLong();
    static final java.security.SecureRandom RND = new java.security.SecureRandom();
    static final String RUN = Long.toString(System.currentTimeMillis() / 1000, 36).toUpperCase();   // keeps window ids unique across restarts
    static String nextBatchId() { return "B" + RUN + "-" + BATCH_SEQ.incrementAndGet(); }
    static Batch current = new Batch(nextBatchId());

    // ---------- screening rules (inert text matching only) ----------
    static Pattern p(String re) { return Pattern.compile(re); }
    static final List<Rule> RULES = List.of(
        new Rule("SQLI", 5, p("(?i)\\bunion\\b.{0,20}\\bselect\\b")),
        new Rule("SQLI", 4, p("(?i)\\b(or|and)\\b\\s+['\"]?\\w+['\"]?\\s*=\\s*['\"]?\\w+")),
        new Rule("SQLI", 5, p("(?i)['\"]\\s*(or|and)\\s+['\"]?\\w+['\"]?\\s*=\\s*['\"]?\\w+")),
        new Rule("SQLI", 5, p("(?i)\\b(drop|alter|truncate)\\s+table\\b")),
        new Rule("SQLI", 4, p("(?i)\\b(sleep|benchmark|pg_sleep|waitfor\\s+delay)\\b\\s*[(']")),
        new Rule("SQLI", 2, p("['\"]\\s*(--|#|/\\*)")),
        new Rule("XSS",  5, p("(?i)<\\s*script\\b")),
        new Rule("XSS",  4, p("(?i)\\bon(error|load|click|mouseover)\\s*=")),
        new Rule("XSS",  4, p("(?i)javascript\\s*:")),
        new Rule("XSS",  2, p("(?i)<\\s*(img|svg|iframe)\\b")),
        new Rule("TRAVERSAL", 5, p("(\\.\\./|\\.\\.\\\\){2,}")),
        new Rule("TRAVERSAL", 5, p("(?i)(/etc/passwd|/etc/shadow|boot\\.ini|win\\.ini)")),
        new Rule("CMDI", 5, p("(?i)(;|\\||&&|`|\\$\\()\\s*(cat|ls|wget|curl|nc|bash|sh|powershell|whoami|id)\\b")),
        new Rule("SSRF", 3, p("(?i)(169\\.254\\.169\\.254|metadata\\.google\\.internal)")),
        new Rule("JNDI", 5, p("(?i)\\$\\{\\s*jndi\\s*:")),
        new Rule("TEMPLATE", 2, p("\\{\\{.{1,40}\\}\\}"))
    );
    static final Pattern SECRET = Pattern.compile("(?i)(\"?(password|passwd|pwd|token|secret|api[_-]?key|authorization|cookie|session)\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^&\\s,;}]+)");
    static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/");

    static Scan scan(String text) {
        int score = 0; Set<String> labels = new LinkedHashSet<>();
        for (Rule r : RULES) if (r.p().matcher(text).find()) { score += r.weight(); labels.add(r.cls()); }
        return new Scan(score, labels);
    }
    static String urlDecode(String s) {
        try { return URLDecoder.decode(s.replace("+", " "), StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { return s; }
    }
    /** deep clean used on the recycle pass: repeated decoding, inline-comment removal, whitespace collapse */
    static String normalise(String s) {
        String x = s;
        for (int i = 0; i < 3; i++) { String d = urlDecode(x); if (d.equals(x)) break; x = d; }
        x = COMMENT.matcher(x).replaceAll("");
        return x.replaceAll("\\s+", " ");
    }
    static boolean obfuscated(String raw) {
        return raw.contains("%25") || raw.contains("%00") || COMMENT.matcher(raw).find();
    }

    // ---------- pipeline ----------
    static synchronized Batch window() { return current; }

    static synchronized void rollBatch() {
        Batch old = current; old.closedAt = System.currentTimeMillis();
        current = new Batch(nextBatchId());
        CLOSED.addFirst(old); while (CLOSED.size() > 20) CLOSED.removeLast();
        long now = System.currentTimeMillis();
        LIVE.values().removeIf(c -> c.doneAt > 0 && now - c.doneAt > 60_000);
        TIMER.execute(() -> process(old));
    }

    static void process(Batch b) {
        for (Cargo c : b.cargo) {
            try {
                String text = c.inspectText();
                Scan s = scan(urlDecode(text));                      // pass 1: single decode only
                if (obfuscated(text)) s = s.plus(2, "OBFUSCATION");
                if (c.oversize) s = s.plus(2, "OVERSIZE");
                if (b.ipLog.getOrDefault(c.ip, 0) > BURST_SOFT) s = s.plus(2, "BURST");
                classify(c, s, 1);
            } catch (Exception e) { event("ERROR", c.id + " screening failed: " + e); c.state = "CLEAN"; c.doneAt = System.currentTimeMillis(); }
        }
    }

    static void classify(Cargo c, Scan s, int pass) {
        c.score = s.score(); c.labels.clear(); c.labels.addAll(s.labels());
        long now = System.currentTimeMillis();
        if (s.score() >= ATTACK_AT) {
            c.state = "ATTACK"; c.sinkAt = now + SINK_MS; flag(c.ip);
            event("WARN", "[" + c.id + " " + mask(c.ip) + "] ATTACK " + s.labels() + " score=" + s.score() + " sink in " + SINK_MS / 1000 + "s");
            activity(c, "ATTACK", "Threat detected: " + String.join(", ", c.labels) + " (score " + c.score + ")");
            TIMER.schedule(() -> sink(c), SINK_MS, TimeUnit.MILLISECONDS);
        } else if (s.score() > 0 && pass == 1) {
            c.state = "IRREGULAR"; c.recycles++; flag(c.ip);
            event("INFO", "[" + c.id + " " + mask(c.ip) + "] IRREGULAR " + s.labels() + " score=" + s.score() + " recycling");
            activity(c, "IRREGULAR", "Irregular activity sent for re-check");
            TIMER.schedule(() -> recycle(c), RECYCLE_MS, TimeUnit.MILLISECONDS);
        } else if (s.score() > 0) {
            c.state = "CLEARED"; c.doneAt = now;
            event("INFO", "[" + c.id + " " + mask(c.ip) + "] CLEARED after recycle score=" + s.score());
            finishSandbox(c, "CLEARED");
        } else { c.state = "CLEAN"; c.doneAt = now; }
        if (c.state.equals("CLEAN")) finishSandbox(c, "CLEAN");
    }

    /** second look: deeper normalisation plus the sender's recent history */
    static void recycle(Cargo c) {
        Scan s = scan(normalise(c.inspectText()));
        if (recentFlags(c.ip, 60_000) >= 3) s = s.plus(2, "REPEAT");
        classify(c, s, 2);
    }

    static void flag(String ip) { FLAGS.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>()).add(System.currentTimeMillis()); }
    static int recentFlags(String ip, long windowMs) {
        Deque<Long> d = FLAGS.get(ip); if (d == null) return 0;
        long cut = System.currentTimeMillis() - windowMs;
        while (!d.isEmpty() && d.peekFirst() < cut) d.pollFirst();
        return d.size();
    }

    /** the sink: swallow the payload, strip it, identify the sender, file the POI, decide on blacklisting */
    static synchronized void sink(Cargo c) {
        String sample = strip(c.body == null ? "" : c.body);
        String sig = sha(normalise(c.inspectText())).substring(0, 12);
        Poi p = POIS.computeIfAbsent(c.ip, k -> new Poi(k, c.ts));
        p.lastSeen = System.currentTimeMillis();
        p.classes.addAll(c.labels); p.sigs.add(sig); p.fps.add(c.fp);
        if (c.device != null) p.devices.add(c.device);
        p.samples.addFirst(c.id + " " + sample); while (p.samples.size() > 5) p.samples.removeLast();
        boolean counts = !c.sandbox || SANDBOX_STRIKES;
        if (counts && p.batches.add(c.batchId)) p.strikes++;      // one strike per distinct window
        c.body = null; c.state = "SUNK"; c.doneAt = System.currentTimeMillis();
        event("INFO", "[" + c.id + " " + mask(c.ip) + "] SUNK sig=" + sig + " poi strikes=" + p.strikes + (counts ? "" : " (sandbox, no strike)"));
        finishSandbox(c, "SUNK", sig);
        if (p.strikes >= 2) blacklist(p);
        persist();
    }

    static void blacklist(Poi p) {
        long until = BL_HOURS <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + BL_HOURS * 3_600_000L;
        if (BL_IP.put(p.ip, until) == null)
            event("ALERT", "BLACKLISTED " + mask(p.ip) + " classes=" + p.classes);
        for (String d : p.devices) BL_DEVICE.put(d, until);
    }

    /** the stripper: redact secrets, neutralise markup, drop control chars, truncate. Raw payload is never kept. */
    static String strip(String raw) {
        String s = SECRET.matcher(raw).replaceAll("$1[REDACTED]");
        s = s.replaceAll("\\p{Cntrl}", " ").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    static boolean blocked(String ip, String device) {
        long now = System.currentTimeMillis();
        Long a = BL_IP.get(ip); if (a != null) { if (a > now) return true; BL_IP.remove(ip); }
        if (device != null) { Long b = BL_DEVICE.get(device); if (b != null) { if (b > now) return true; BL_DEVICE.remove(device); } }
        return false;
    }

    // ---------- http ----------
    public static void install(HttpServer server, EventLog log) {
        LOG = log;
        load();
        TIMER.scheduleAtFixedRate(Sentinel::rollBatch, BATCH_MS, BATCH_MS, TimeUnit.MILLISECONDS);
        server.createContext("/sandbox", Sentinel::sandbox);
        server.createContext("/api/sentinel", Sentinel::api);
        event("INFO", "online | window " + BATCH_MS + "ms | sink " + SINK_MS / 1000 + "s | blacklist " + (BL_HOURS <= 0 ? "permanent" : BL_HOURS + "h")
            + " | proxy-trust " + TRUST_PROXY + " | sandbox strikes " + SANDBOX_STRIKES);
    }

    /** Gate for live RTPE routes: blocked senders get a 403, everyone else is logged as cargo. Returns true if the request was answered. */
    public static boolean gate(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) return false;
        String ip = clientIp(ex), device = deviceId(ex);
        if (blocked(ip, device)) { BLOCKED.incrementAndGet(); send(ex, 403, "{\"error\":\"blocked by sentinel\"}"); ex.close(); return true; }
        Batch b = window();
        if (b.ipLog.merge(ip, 1, Integer::sum) > BURST_HARD) { send(ex, 429, "{\"error\":\"too many requests in this window\"}"); ex.close(); return true; }
        if (LIVE.size() < MAX_LIVE) ingest(ex, b, ip, device, "", false, false);
        return false;
    }

    static Cargo ingest(HttpExchange ex, Batch b, String ip, String device, String body, boolean oversize, boolean sandbox) {
        String q = ex.getRequestURI().getRawQuery();
        String user = sandbox ? Accounts.userOf(ex) : null;
        Cargo c = new Cargo("CRG-" + Long.toString(SEQ.incrementAndGet(), 36).toUpperCase() + Integer.toString(RND.nextInt(1296), 36).toUpperCase(),
            ip, fingerprint(ex), device, ex.getRequestMethod(), ex.getRequestURI().getRawPath() + (q == null ? "" : "?" + q), body, b.id, sandbox, oversize,
            user, Accounts.who(user, ip, device));
        LIVE.put(c.id, c); b.cargo.add(c);
        if (sandbox) activity(c, "RECEIVED", "Payload received by Sentinel sandbox");
        return c;
    }

    static void activity(Cargo c, String kind, String text) {
        if (c.sandbox) Accounts.emit(c.who, kind, c.id, text);
    }

    static void finishSandbox(Cargo c, String outcome) {
        if (!c.sandbox) return;
        finishSandbox(c, outcome, sha(normalise(c.inspectText())).substring(0, 12));
    }

    static void finishSandbox(Cargo c, String outcome, String signature) {
        if (!c.sandbox) return;
        activity(c, outcome, "Test completed: " + outcome + (c.labels.isEmpty() ? "" : " · " + String.join(", ", c.labels)));
        if (c.user != null) Accounts.contribute(c.user, signature, outcome, c.labels);
    }

    /** the test window: POST /sandbox with any text, GET /sandbox/{id} for your own receipt */
    static void sandbox(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath(), method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) { send(ex, 204, ""); return; }
            String ip = clientIp(ex), device = deviceId(ex);
            if (blocked(ip, device)) { BLOCKED.incrementAndGet(); send(ex, 403, "{\"error\":\"blocked by sentinel\"}"); return; }
            Batch b = window();
            if (b.ipLog.merge(ip, 1, Integer::sum) > BURST_HARD) { send(ex, 429, "{\"error\":\"too many requests in this window\"}"); return; }
            if (LIVE.size() >= MAX_LIVE) { send(ex, 503, "{\"error\":\"sentinel at capacity\"}"); return; }

            if (method.equals("GET") && path.startsWith("/sandbox/")) {
                Cargo c = LIVE.get(path.substring(9));
                if (c == null || !c.ip.equals(ip)) { send(ex, 404, "{\"error\":\"unknown cargo\"}"); return; }
                send(ex, 200, json(view(c, false))); return;
            }
            if (!method.equals("POST") || !path.equals("/sandbox")) { send(ex, 405, "{\"error\":\"POST /sandbox\"}"); return; }

            byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            boolean oversize = raw.length > MAX_BODY;
            String body = new String(raw, 0, Math.min(raw.length, MAX_BODY), StandardCharsets.UTF_8);
            Cargo c = ingest(ex, b, ip, device, body, oversize, true);
            Map<String, Object> out = view(c, false);
            out.put("windowClosesInMs", Math.max(0, b.openedAt + BATCH_MS - System.currentTimeMillis()));
            send(ex, 202, json(out));
        } catch (Exception e) {
            event("ERROR", "sandbox handler: " + e);
            send(ex, 500, "{\"error\":\"internal\"}");
        } finally { ex.close(); }
    }

    static void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath(), method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) { send(ex, 204, ""); return; }
            if (path.equals("/api/sentinel/feed")) { feed(ex); return; }
            admin(ex, path, method);
        } catch (Exception e) {
            event("ERROR", "sentinel api: " + e);
            send(ex, 500, "{\"error\":\"internal\"}");
        } finally { ex.close(); }
    }

    /** public view for the cockpit: your own cargo and aggregate counts only, never other people's IPs */
    static void feed(HttpExchange ex) throws IOException {
        String ip = clientIp(ex), device = deviceId(ex);
        Batch cur = window();
        Map<String, Integer> counts = new TreeMap<>();
        for (Cargo c : LIVE.values()) counts.merge(c.state, 1, Integer::sum);
        List<Object> mine = LIVE.values().stream()
            .filter(c -> c.ip.equals(ip) || (device != null && device.equals(c.device)))
            .sorted(Comparator.comparingLong((Cargo c) -> c.ts).reversed()).limit(8).map(c -> (Object) view(c, false)).toList();
        Poi me = POIS.get(ip);
        send(ex, 200, json(obj(
            "you", obj("ip", mask(ip), "device", device == null ? null : device.substring(0, Math.min(8, device.length())), "machine", fingerprint(ex).substring(0, 8),
                       "blocked", blocked(ip, device), "strikes", me == null ? 0 : me.strikes),
            "window", obj("id", cur.id, "closesInMs", Math.max(0, cur.openedAt + BATCH_MS - System.currentTimeMillis())),
            "config", obj("windowMs", BATCH_MS, "sinkMs", SINK_MS, "recycleMs", RECYCLE_MS, "sandboxStrikes", SANDBOX_STRIKES),
            "counts", counts, "poi", POIS.size(), "blacklisted", BL_IP.size(), "blockedRequests", BLOCKED.get(), "mine", mine)));
    }

    static void admin(HttpExchange ex, String path, String method) throws IOException {
        if (!isAdmin(ex)) { send(ex, 401, "{\"error\":\"admin only (set ADMIN_TOKEN and send X-Admin-Token)\"}"); return; }
        switch (path) {
            case "/api/sentinel/state" -> send(ex, 200, json(state()));
            case "/api/sentinel/poi" -> send(ex, 200, json(POIS.values().stream().map(Sentinel::poiView).toList()));
            case "/api/sentinel/unblock" -> {
                String ip = queryParam(ex, "ip");
                if (ip == null) { send(ex, 400, "{\"error\":\"ip required\"}"); return; }
                Poi p = POIS.get(ip); BL_IP.remove(ip);
                if (p != null) { p.strikes = 0; p.batches.clear(); p.devices.forEach(BL_DEVICE::remove); persist(); }
                event("INFO", "UNBLOCKED " + mask(ip)); send(ex, 200, "{\"ok\":true}");
            }
            case "/api/sentinel/reset" -> {
                if (!method.equals("POST")) { send(ex, 405, "{\"error\":\"POST only\"}"); return; }
                LIVE.clear(); POIS.clear(); BL_IP.clear(); BL_DEVICE.clear(); FLAGS.clear(); EVENTS.clear(); CLOSED.clear(); persist();
                send(ex, 200, "{\"ok\":true}");
            }
            default -> send(ex, 404, "{\"error\":\"unknown admin route\"}");
        }
    }

    static boolean isAdmin(HttpExchange ex) {
        if (!ADMIN_TOKEN.isEmpty()) {
            String t = ex.getRequestHeaders().getFirst("X-Admin-Token");
            return t != null && MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), ADMIN_TOKEN.getBytes(StandardCharsets.UTF_8));
        }
        return !TRUST_PROXY && ex.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    static String clientIp(HttpExchange ex) {
        if (TRUST_PROXY) {                                       // only enable behind a proxy you control (e.g. Render)
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null) { String first = xff.split(",")[0].trim(); if (first.matches("[0-9a-fA-F:.]{2,45}")) return first; }
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }
    static String deviceId(HttpExchange ex) {                    // random id the client generates and keeps; not a credential
        String d = ex.getRequestHeaders().getFirst("X-Device-Id");
        return d != null && d.matches("[A-Za-z0-9_-]{8,64}") ? d : null;
    }
    static String fingerprint(HttpExchange ex) {                 // coarse machine label: logged on POIs, never used alone to block
        var h = ex.getRequestHeaders();
        return sha(String.join("|", String.valueOf(h.getFirst("User-Agent")), String.valueOf(h.getFirst("Accept-Language")), String.valueOf(h.getFirst("Accept-Encoding")))).substring(0, 16);
    }
    static String queryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery(); if (q == null) return null;
        for (String kv : q.split("&")) { String[] p = kv.split("=", 2); if (p[0].equals(key) && p.length == 2) return urlDecode(p[1]); }
        return null;
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Cache-Control", "no-store");
        h.set("Access-Control-Allow-Origin", ALLOW_ORIGIN);
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Admin-Token, X-Device-Id");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        if (code == 204) { ex.sendResponseHeaders(204, -1); return; }
        ex.sendResponseHeaders(code, out.length);
        ex.getResponseBody().write(out);
    }

    // ---------- views ----------
    static Map<String, Object> obj(Object... kv) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }

    static Map<String, Object> view(Cargo c, boolean admin) {
        Map<String, Object> m = obj("id", c.id, "state", c.state, "labels", c.labels, "score", c.score, "window", c.batchId,
            "recycles", c.recycles, "sandbox", c.sandbox, "receivedAt", c.ts);
        if (c.state.equals("ATTACK")) m.put("sinkInMs", Math.max(0, c.sinkAt - System.currentTimeMillis()));
        if (admin) { m.put("ip", c.ip); m.put("machine", c.fp); m.put("device", c.device); m.put("sample", c.body == null ? null : strip(c.body)); }
        return m;
    }
    static Map<String, Object> poiView(Poi p) {
        Long until = BL_IP.get(p.ip);
        return obj("ip", p.ip, "firstSeen", p.firstSeen, "lastSeen", p.lastSeen, "strikes", p.strikes, "classes", p.classes, "signatures", p.sigs,
            "machines", p.fps, "devices", p.devices, "samples", new ArrayList<>(p.samples), "blacklisted", until != null && until > System.currentTimeMillis());
    }
    static Map<String, Object> batchView(Batch b) {
        return obj("id", b.id, "openedAt", b.openedAt, "closedAt", b.closedAt, "size", b.cargo.size(), "ipLog", b.ipLog);
    }
    static Map<String, Object> state() {
        Batch cur = window();
        return obj("now", System.currentTimeMillis(),
            "config", obj("batchMs", BATCH_MS, "recycleMs", RECYCLE_MS, "sinkMs", SINK_MS, "attackScore", ATTACK_AT, "blacklistHours", BL_HOURS, "sandboxStrikes", SANDBOX_STRIKES),
            "window", batchView(cur), "recentWindows", CLOSED.stream().map(Sentinel::batchView).toList(),
            "cargo", LIVE.values().stream().sorted(Comparator.comparingLong((Cargo c) -> c.ts).reversed()).limit(100).map(c -> view(c, true)).toList(),
            "poi", POIS.values().stream().map(Sentinel::poiView).toList(),
            "blacklist", obj("ips", BL_IP.keySet(), "devices", BL_DEVICE.keySet()), "blockedRequests", BLOCKED.get(),
            "events", new ArrayList<>(EVENTS));
    }

    // ---------- helpers ----------
    static void event(String level, String msg) {
        String line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + " " + level + " " + msg;
        EVENTS.addLast(line); while (EVENTS.size() > 300) EVENTS.pollFirst();
        EventLog l = LOG;
        if (l != null) l.log(level.equals("ALERT") ? "WARN" : level, "%s", "[sentinel] " + msg);
    }
    /** public labels never show a full IP: 10.0.0.x / 2001:db8:: style */
    static String mask(String ip) {
        if (ip == null) return "?";
        if (ip.contains(":")) { String[] g = ip.split(":"); return (g.length > 1 ? g[0] + ":" + g[1] : ip) + "::x"; }
        int i = ip.lastIndexOf('.');
        return i < 0 ? ip : ip.substring(0, i) + ".x";
    }
    static String sha(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) switch (ch) {
            case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
            default -> { if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
        }
        return b.toString();
    }
    static String json(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return "\"" + esc(s) + "\"";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?, ?> m) { StringJoiner j = new StringJoiner(",", "{", "}"); m.forEach((k, v) -> j.add("\"" + esc(String.valueOf(k)) + "\":" + json(v))); return j.toString(); }
        if (o instanceof Collection<?> c) { StringJoiner j = new StringJoiner(",", "[", "]"); for (Object x : c) j.add(json(x)); return j.toString(); }
        return json(o.toString());
    }

    // ---------- persistence: POIs and blacklist survive restarts (tab-separated, atomic write) ----------
    static String csv(Collection<String> c) { return String.join(",", c); }
    static Set<String> unCsv(String s) { return s.isEmpty() ? new TreeSet<>() : new TreeSet<>(Arrays.asList(s.split(","))); }

    static synchronized void persist() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Poi p : POIS.values())
                sb.append("POI\t").append(p.ip).append('\t').append(p.firstSeen).append('\t').append(p.lastSeen).append('\t').append(p.strikes).append('\t')
                  .append(csv(p.classes)).append('\t').append(csv(p.sigs)).append('\t').append(csv(p.devices)).append('\t').append(csv(p.fps)).append('\t').append(csv(p.batches)).append('\n');
            BL_IP.forEach((k, v) -> sb.append("BLIP\t").append(k).append('\t').append(v).append('\n'));
            BL_DEVICE.forEach((k, v) -> sb.append("BLDV\t").append(k).append('\t').append(v).append('\n'));
            Path tmp = STORE.resolveSibling(STORE.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString());
            Files.move(tmp, STORE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) { event("ERROR", "persist failed: " + e); }
    }

    static void load() {
        if (!Files.exists(STORE)) return;
        try {
            for (String line : Files.readAllLines(STORE)) {
                String[] f = line.split("\t", -1);
                switch (f[0]) {
                    case "POI" -> { Poi p = new Poi(f[1], Long.parseLong(f[2])); p.lastSeen = Long.parseLong(f[3]); p.strikes = Integer.parseInt(f[4]);
                        p.classes.addAll(unCsv(f[5])); p.sigs.addAll(unCsv(f[6])); p.devices.addAll(unCsv(f[7])); p.fps.addAll(unCsv(f[8])); p.batches.addAll(unCsv(f[9])); POIS.put(p.ip, p); }
                    case "BLIP" -> BL_IP.put(f[1], Long.parseLong(f[2]));
                    case "BLDV" -> BL_DEVICE.put(f[1], Long.parseLong(f[2]));
                    default -> { }
                }
            }
            System.out.println("Loaded " + POIS.size() + " POIs, " + BL_IP.size() + " blacklisted IPs from " + STORE);
        } catch (Exception e) { System.out.println("Could not load " + STORE + ": " + e); }
    }
}