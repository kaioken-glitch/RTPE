package rtpe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * User accounts, sessions and community contribution data for the Sentinel lab.
 *
 * Passwords: PBKDF2-HMAC-SHA256, per-user random salt, constant-time compare, never logged or returned.
 * Sessions: random 256-bit bearer tokens; only a SHA-256 of the token is kept server-side.
 * Contributions: a user earns points once per distinct payload signature, so replaying the same text earns nothing.
 * The public activity stream shows account names or "guest-xxxx" labels, event kinds and findings, never payload text or IPs.
 */
public final class Accounts {

    private Accounts() {}

    // ---------- config ----------
    static final Path USERS_FILE = Path.of(Sentinel.env("USERS_FILE", "users.tsv"));
    static final Path CONTRIB_FILE = Path.of(Sentinel.env("CONTRIB_FILE", "contrib.tsv"));
    static final Path SUPABASE_USERS_FILE = Path.of(Sentinel.env("SUPABASE_USERS_FILE", "supabase-users.tsv"));
    static final String SUPABASE_URL = Sentinel.env("SUPABASE_URL", "");
    static final String SUPABASE_ANON_KEY = Sentinel.env("SUPABASE_ANON_KEY", "");
    static final HttpClient SUPABASE_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Pattern SUPABASE_ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
    static final Pattern SUPABASE_EMAIL = Pattern.compile("\"email\"\\s*:\\s*\"([^\"\\s]+@[^\"\\s]+)\"");
    static final int ITER = Integer.parseInt(Sentinel.env("PBKDF2_ITER", "210000"));
    static final long SESSION_MS = 7L * 24 * 3_600_000;
    static final long LOGIN_WINDOW_MS = 10 * 60_000, REG_WINDOW_MS = 3_600_000, EARN_WINDOW_MS = 3_600_000;
    static final int LOGIN_FAILS_PER_IP = 10, LOGIN_FAILS_PER_USER = 5, REGS_PER_IP = 5, NOVEL_PER_HOUR = 60;
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,24}");
    static final byte[] DUMMY_SALT = new byte[16];
    static final SecureRandom RND = Sentinel.RND;
    static final String GUEST_SALT = HexFormat.of().formatHex(randomBytes(16));

    // ---------- model ----------
    record User(String name, byte[] salt, byte[] hash, int iter, long created) {}
    record Session(String user, long exp) {}

    static final class Stats {
        long tests, clean, cleared, attacks, firsts, points, lastActive;
        final Set<String> sigs = new HashSet<>();
        final Deque<Long> novel = new ArrayDeque<>();
    }

    static final Map<String, User> USERS = new ConcurrentHashMap<>();       // key: lower-case name
    static final Map<String, User> SUPABASE_USERS = new ConcurrentHashMap<>(); // key: Supabase user id
    static final Set<String> SUPABASE_NAMES = ConcurrentHashMap.newKeySet();
    static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>(); // key: sha256(token)
    static final Map<String, Stats> STATS = new ConcurrentHashMap<>();
    static final Map<String, String> SIG_FIRST = new ConcurrentHashMap<>(); // signature -> first contributor
    static final Map<String, Deque<Long>> ATTEMPTS = new ConcurrentHashMap<>();

    // ---------- public activity stream ----------
    static final Deque<Map<String, Object>> ACTIVITY = new ConcurrentLinkedDeque<>();
    static final AtomicLong ACT_SEQ = new AtomicLong();

    static byte[] randomBytes(int n) { byte[] b = new byte[n]; new SecureRandom().nextBytes(b); return b; }

    public static String who(String user, String ip, String device) {
        return user != null ? user : "guest-" + Sentinel.sha(GUEST_SALT + ip + "|" + device).substring(0, 4);
    }

    public static void emit(String who, String kind, String id, String text) {
        ACTIVITY.addLast(Sentinel.obj("seq", ACT_SEQ.incrementAndGet(), "ts", System.currentTimeMillis(),
                "who", who, "kind", kind, "id", id, "text", text));
        while (ACTIVITY.size() > 200) ACTIVITY.pollFirst();
    }

    // ---------- install ----------
    public static void install(HttpServer server) {
        load();
        server.createContext("/api/auth", Accounts::auth);
        server.createContext("/api/community", Accounts::community);
        System.out.println("Accounts: " + USERS.size() + " users, " + SIG_FIRST.size() + " signatures loaded");
    }

    // ---------- hashing ----------
    static byte[] pbkdf2(String password, byte[] salt, int iter) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iter, 256)).getEncoded();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    // ---------- rate limits ----------
    static boolean tooMany(String key, int limit, long windowMs) {
        Deque<Long> d = ATTEMPTS.get(key);
        if (d == null) return false;
        long cut = System.currentTimeMillis() - windowMs;
        synchronized (d) {
            while (!d.isEmpty() && d.peekFirst() < cut) d.pollFirst();
            return d.size() >= limit;
        }
    }
    static void record(String key) {
        Deque<Long> d = ATTEMPTS.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) { d.addLast(System.currentTimeMillis()); }
    }

    // ---------- http ----------
    static void auth(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath(), method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) { Sentinel.send(ex, 204, ""); return; }
            switch (path) {
                case "/api/auth/register" -> { if (post(ex, method)) register(ex); }
                case "/api/auth/login" -> { if (post(ex, method)) login(ex); }
                case "/api/auth/supabase" -> { if (post(ex, method)) supabase(ex); }
                case "/api/auth/logout" -> { if (post(ex, method)) logout(ex); }
                case "/api/auth/me" -> me(ex);
                default -> Sentinel.send(ex, 404, "{\"error\":\"unknown route\"}");
            }
        } catch (Exception e) {
            Sentinel.event("ERROR", "accounts: " + e);
            Sentinel.send(ex, 500, "{\"error\":\"internal\"}");
        } finally { ex.close(); }
    }

    static boolean post(HttpExchange ex, String method) throws IOException {
        if (method.equals("POST")) return true;
        Sentinel.send(ex, 405, "{\"error\":\"POST only\"}");
        return false;
    }

    static Map<String, String> form(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(2049);
        Map<String, String> out = new HashMap<>();
        if (raw.length > 2048) return out;
        for (String pair : new String(raw, StandardCharsets.UTF_8).split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    static void err(HttpExchange ex, int code, String msg) throws IOException {
        Sentinel.send(ex, code, Sentinel.json(Sentinel.obj("error", msg)));
    }

    static void register(HttpExchange ex) throws IOException {
        String ip = Sentinel.clientIp(ex), device = Sentinel.deviceId(ex);
        if (Sentinel.blocked(ip, device)) { err(ex, 403, "blocked by sentinel"); return; }
        if (tooMany("reg:" + ip, REGS_PER_IP, REG_WINDOW_MS)) { err(ex, 429, "too many sign-ups from this address, try later"); return; }
        Map<String, String> f = form(ex);
        String name = f.getOrDefault("username", "").trim(), pw = f.getOrDefault("password", "");
        if (!NAME.matcher(name).matches()) { err(ex, 400, "username must be 3-24 letters, numbers or underscores"); return; }
        if (pw.length() < 10 || pw.length() > 128) { err(ex, 400, "password must be 10-128 characters"); return; }
        if (pw.equalsIgnoreCase(name)) { err(ex, 400, "password must differ from the username"); return; }

        byte[] salt = randomBytes(16);
        User u = new User(name, salt, pbkdf2(pw, salt, ITER), ITER, System.currentTimeMillis());
        if (USERS.putIfAbsent(name.toLowerCase(Locale.ROOT), u) != null) { err(ex, 409, "username is taken"); return; }
        record("reg:" + ip);
        persistUsers();
        Sentinel.event("INFO", "account created: " + name);
        Sentinel.send(ex, 201, Sentinel.json(Sentinel.obj("token", issue(u), "user", profile(u))));
    }

    static void login(HttpExchange ex) throws IOException {
        String ip = Sentinel.clientIp(ex), device = Sentinel.deviceId(ex);
        if (Sentinel.blocked(ip, device)) { err(ex, 403, "blocked by sentinel"); return; }
        Map<String, String> f = form(ex);
        String name = f.getOrDefault("username", "").trim(), pw = f.getOrDefault("password", "");
        String lower = name.toLowerCase(Locale.ROOT);
        if (tooMany("ip:" + ip, LOGIN_FAILS_PER_IP, LOGIN_WINDOW_MS) || tooMany("u:" + lower, LOGIN_FAILS_PER_USER, LOGIN_WINDOW_MS)) {
            err(ex, 429, "too many failed attempts, try again in a few minutes"); return;
        }
        if (pw.isEmpty() || pw.length() > 128) { err(ex, 400, "enter your username and password"); return; }
        User u = USERS.get(lower);
        // hash even for unknown users so response time does not reveal which usernames exist
        byte[] got = pbkdf2(pw, u == null ? DUMMY_SALT : u.salt(), u == null ? ITER : u.iter());
        if (u == null || !MessageDigest.isEqual(got, u.hash())) {
            record("ip:" + ip); record("u:" + lower);
            err(ex, 401, "wrong username or password"); return;
        }
        ATTEMPTS.remove("u:" + lower);
        Sentinel.send(ex, 200, Sentinel.json(Sentinel.obj("token", issue(u), "user", profile(u))));
    }

    static void supabase(HttpExchange ex) throws IOException {
        String ip = Sentinel.clientIp(ex);
        String device = Sentinel.deviceId(ex);
        if (Sentinel.blocked(ip, device)) { err(ex, 403, "blocked by sentinel"); return; }
        if (SUPABASE_URL.isBlank() || SUPABASE_ANON_KEY.isBlank()) {
            err(ex, 503, "Supabase authentication is not configured on the backend");
            return;
        }
        String token = bearer(ex);
        if (token == null || token.length() > 8192) { err(ex, 401, "invalid Supabase session"); return; }
        String rateKey = "supabase:" + ip;
        if (tooMany(rateKey, 60, 60_000)) { err(ex, 429, "too many sign-in requests, try again shortly"); return; }
        record(rateKey);

        try {
            URI endpoint = URI.create(SUPABASE_URL.replaceAll("/+$", "") + "/auth/v1/user");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = SUPABASE_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500 || response.statusCode() == 429) {
                err(ex, 503, "Supabase authentication is temporarily unavailable");
                return;
            }
            if (response.statusCode() != 200) { err(ex, 401, "invalid or expired Supabase session"); return; }

            var idMatch = SUPABASE_ID.matcher(response.body());
            var emailMatch = SUPABASE_EMAIL.matcher(response.body());
            if (!idMatch.find() || !emailMatch.find()) { err(ex, 401, "Supabase returned an incomplete user profile"); return; }
            String id;
            try { id = UUID.fromString(idMatch.group(1)).toString(); }
            catch (IllegalArgumentException e) { err(ex, 401, "Supabase returned an invalid user id"); return; }

            User user = supabaseUser(id, emailMatch.group(1));
            Sentinel.send(ex, 200, Sentinel.json(Sentinel.obj("token", issue(user), "user", profile(user))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err(ex, 503, "Supabase authentication was interrupted; try again");
        } catch (IOException e) {
            Sentinel.event("WARN", "Supabase sign-in could not be completed: " + e.getClass().getSimpleName());
            err(ex, 503, "Supabase sign-in could not be completed; try again");
        } catch (IllegalArgumentException e) {
            Sentinel.event("ERROR", "invalid Supabase authentication configuration");
            err(ex, 503, "Supabase authentication is misconfigured on the backend");
        }
    }

    static synchronized User supabaseUser(String id, String email) throws IOException {
        User existing = SUPABASE_USERS.get(id);
        if (existing != null) return existing;

        String local = email.substring(0, email.indexOf('@')).replaceAll("[^A-Za-z0-9_]", "");
        String base = local.length() > 24 ? local.substring(0, 24) : local;
        String compactId = id.replace("-", "");
        if (!NAME.matcher(base).matches()) base = "user_" + compactId.substring(0, 8);

        for (int suffixLength = 0; suffixLength <= 23; suffixLength += suffixLength == 0 ? 4 : 1) {
            String suffix = suffixLength == 0 ? "" : "_" + compactId.substring(0, suffixLength);
            int prefixLength = Math.min(base.length(), 24 - suffix.length());
            String name = base.substring(0, prefixLength) + suffix;
            String key = name.toLowerCase(Locale.ROOT);
            User user = new User(name, randomBytes(16), randomBytes(32), ITER, System.currentTimeMillis());
            if (USERS.putIfAbsent(key, user) == null) {
                SUPABASE_NAMES.add(key);
                SUPABASE_USERS.put(id, user);
                try {
                    persistSupabaseUsers();
                } catch (IOException e) {
                    SUPABASE_USERS.remove(id, user);
                    SUPABASE_NAMES.remove(key);
                    USERS.remove(key, user);
                    throw e;
                }
                return user;
            }
        }
        throw new IllegalStateException("could not allocate a Sentinel display name");
    }

    static void logout(HttpExchange ex) throws IOException {
        String t = bearer(ex);
        if (t != null) SESSIONS.remove(Sentinel.sha(t));
        Sentinel.send(ex, 200, "{\"ok\":true}");
    }

    static void me(HttpExchange ex) throws IOException {
        String name = userOf(ex);
        User u = name == null ? null : USERS.get(name.toLowerCase(Locale.ROOT));
        if (u == null) { err(ex, 401, "not signed in"); return; }
        Sentinel.send(ex, 200, Sentinel.json(profile(u)));
    }

    static String issue(User u) {
        long now = System.currentTimeMillis();
        SESSIONS.values().removeIf(s -> s.exp() < now);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        SESSIONS.put(Sentinel.sha(token), new Session(u.name(), now + SESSION_MS));
        return token;
    }

    static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") && h.length() < 200 ? h.substring(7).trim() : null;
    }

    /** the signed-in account for this request, or null */
    public static String userOf(HttpExchange ex) {
        String t = bearer(ex);
        if (t == null) return null;
        String key = Sentinel.sha(t);
        Session s = SESSIONS.get(key);
        if (s == null) return null;
        if (s.exp() < System.currentTimeMillis()) { SESSIONS.remove(key); return null; }
        return s.user();
    }

    static Map<String, Object> profile(User u) {
        Stats s = STATS.get(u.name().toLowerCase(Locale.ROOT));
        long rank = 0;
        if (s != null) { synchronized (Accounts.class) { rank = 1 + STATS.values().stream().filter(o -> o.points > s.points).count(); } }
        return Sentinel.obj("name", u.name(), "createdAt", u.created(), "rank", rank, "stats", statsView(s));
    }

    static synchronized Map<String, Object> statsView(Stats s) {
        if (s == null) s = new Stats();
        return Sentinel.obj("points", s.points, "tests", s.tests, "clean", s.clean, "cleared", s.cleared, "attacks", s.attacks,
                "signatures", s.sigs.size(), "firsts", s.firsts, "lastActive", s.lastActive);
    }

    static void community(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath();
            if (ex.getRequestMethod().equals("OPTIONS")) { Sentinel.send(ex, 204, ""); return; }
            if (path.equals("/api/community/stream")) {
                long since = 0;
                try { since = Long.parseLong(Optional.ofNullable(Sentinel.queryParam(ex, "since")).orElse("0")); } catch (NumberFormatException ignored) { }
                final long after = since;
                List<Object> events = ACTIVITY.stream().filter(e -> (long) e.get("seq") > after).map(e -> (Object) e).toList();
                Sentinel.send(ex, 200, Sentinel.json(Sentinel.obj("events", events, "lastSeq", ACT_SEQ.get())));
            } else if (path.equals("/api/community/leaderboard")) {
                Sentinel.send(ex, 200, Sentinel.json(leaderboard()));
            } else Sentinel.send(ex, 404, "{\"error\":\"unknown route\"}");
        } catch (Exception e) {
            Sentinel.event("ERROR", "community: " + e);
            Sentinel.send(ex, 500, "{\"error\":\"internal\"}");
        } finally { ex.close(); }
    }

    static synchronized Map<String, Object> leaderboard() {
        List<Object> rows = new ArrayList<>();
        long tests = 0, attacks = 0;
        List<Map.Entry<String, Stats>> sorted = new ArrayList<>(STATS.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue().points, a.getValue().points));
        for (Map.Entry<String, Stats> e : sorted) {
            tests += e.getValue().tests; attacks += e.getValue().attacks;
            if (rows.size() < 20) {
                User u = USERS.get(e.getKey());
                Map<String, Object> row = statsView(e.getValue());
                Map<String, Object> out = Sentinel.obj("name", u == null ? e.getKey() : u.name());
                out.putAll(row);
                rows.add(out);
            }
        }
        return Sentinel.obj("members", USERS.size(), "tests", tests, "attacks", attacks, "signatures", SIG_FIRST.size(), "top", rows);
    }

    // ---------- contributions ----------
    static int base(String outcome) { return switch (outcome) { case "SUNK" -> 5; case "CLEARED" -> 2; default -> 1; }; }

    /** Called by Sentinel when a sandbox test by a signed-in user reaches a final state. */
    public static synchronized void contribute(String user, String sig, String outcome, Collection<String> labels) {
        String key = user.toLowerCase(Locale.ROOT);
        Stats s = STATS.computeIfAbsent(key, k -> new Stats());
        long now = System.currentTimeMillis();
        s.tests++; s.lastActive = now;
        switch (outcome) { case "SUNK" -> s.attacks++; case "CLEARED" -> s.cleared++; default -> s.clean++; }

        int pts = 0;
        if (!s.sigs.contains(sig)) {
            while (!s.novel.isEmpty() && s.novel.peekFirst() < now - EARN_WINDOW_MS) s.novel.pollFirst();
            if (s.novel.size() < NOVEL_PER_HOUR) {         // earning cap: new signatures only, per hour
                s.sigs.add(sig); s.novel.addLast(now);
                pts = base(outcome);
                if (SIG_FIRST.putIfAbsent(sig, key) == null) { pts += 3; s.firsts++; }
            }
        }
        s.points += pts;
        try {
            Files.writeString(CONTRIB_FILE, now + "\t" + user + "\t" + sig + "\t" + outcome + "\t" + pts + "\t" + String.join(",", labels) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { Sentinel.event("ERROR", "contribution log failed: " + e); }
    }

    // ---------- persistence ----------
    static synchronized void persistUsers() {
        try {
            StringBuilder sb = new StringBuilder();
            HexFormat hx = HexFormat.of();
            for (Map.Entry<String, User> entry : USERS.entrySet()) {
                if (SUPABASE_NAMES.contains(entry.getKey())) continue;
                User u = entry.getValue();
                sb.append(u.name()).append('\t').append(hx.formatHex(u.salt())).append('\t').append(hx.formatHex(u.hash())).append('\t')
                  .append(u.iter()).append('\t').append(u.created()).append('\n');
            }
            Path tmp = USERS_FILE.resolveSibling(USERS_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString());
            Files.move(tmp, USERS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) { Sentinel.event("ERROR", "could not save users: " + e); }
    }

    static synchronized void persistSupabaseUsers() throws IOException {
        StringBuilder sb = new StringBuilder();
        SUPABASE_USERS.forEach((id, user) -> sb.append(id).append('\t').append(user.name()).append('\t').append(user.created()).append('\n'));
        Path tmp = SUPABASE_USERS_FILE.resolveSibling(SUPABASE_USERS_FILE.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString());
        try {
            Files.move(tmp, SUPABASE_USERS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, SUPABASE_USERS_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void load() {
        HexFormat hx = HexFormat.of();
        try {
            if (Files.exists(USERS_FILE))
                for (String line : Files.readAllLines(USERS_FILE)) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 5) continue;
                    USERS.put(f[0].toLowerCase(Locale.ROOT), new User(f[0], hx.parseHex(f[1]), hx.parseHex(f[2]), Integer.parseInt(f[3]), Long.parseLong(f[4])));
                }
            if (Files.exists(SUPABASE_USERS_FILE))
                for (String line : Files.readAllLines(SUPABASE_USERS_FILE)) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 3 || !NAME.matcher(f[1]).matches()) continue;
                    String id = UUID.fromString(f[0]).toString();
                    String key = f[1].toLowerCase(Locale.ROOT);
                    User user = new User(f[1], randomBytes(16), randomBytes(32), ITER, Long.parseLong(f[2]));
                    if (USERS.putIfAbsent(key, user) == null) {
                        SUPABASE_USERS.put(id, user);
                        SUPABASE_NAMES.add(key);
                    }
                }
            if (Files.exists(CONTRIB_FILE))
                for (String line : Files.readAllLines(CONTRIB_FILE)) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 5) continue;
                    String key = f[1].toLowerCase(Locale.ROOT);
                    Stats s = STATS.computeIfAbsent(key, k -> new Stats());
                    long ts = Long.parseLong(f[0]); int pts = Integer.parseInt(f[4]);
                    s.tests++; s.lastActive = Math.max(s.lastActive, ts);
                    switch (f[3]) { case "SUNK" -> s.attacks++; case "CLEARED" -> s.cleared++; default -> s.clean++; }
                    if (pts > 0) {
                        s.sigs.add(f[2]); s.points += pts;
                        if (pts >= base(f[3]) + 3) { s.firsts++; SIG_FIRST.put(f[2], key); } else SIG_FIRST.putIfAbsent(f[2], key);
                    }
                }
        } catch (Exception e) { System.out.println("Could not load accounts: " + e); }
    }
}
