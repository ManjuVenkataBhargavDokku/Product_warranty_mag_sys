import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.*;

/*
 * Web layer for the Warranty Management System.
 * Reuses DataStore, Product, Customer, Warranty, Claim and Util from your existing
 * Java file, so compile them together:
 *     javac abc.java WarrantyServer.java
 *     java WarrantyServer
 * Then open http://localhost:8080  (it serves the ./web folder).
 * Uses only the JDK's built-in HttpServer (Java 6+), no libraries.
 * Do not run the console menu at the same time: both use warranty_data.ser.
 */
class ApiException extends RuntimeException {
    final int status;
    ApiException(int status, String msg) { super(msg); this.status = status; }
}

class WarrantyServer implements HttpHandler {
    static final String DATA_FILE = "warranty_data.ser";
    static DataStore db;
    final boolean api;

    WarrantyServer(boolean api) { this.api = api; }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        db = DataStore.load(DATA_FILE);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", new WarrantyServer(true));
        server.createContext("/", new WarrantyServer(false));
        server.start();
        System.out.println("Warranty Desk running at http://localhost:" + port);
    }

    // ---------------- HTTP plumbing ----------------

    public void handle(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        String method = ex.getRequestMethod();
        if (method.equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); ex.close(); return; }
        try {
            if (!api) { serveFile(ex); return; }
            Map<String, String> query = params(ex.getRequestURI().getRawQuery());
            Map<String, String> body = new HashMap<String, String>();
            if (method.equals("POST") || method.equals("PUT")) body = params(readAll(ex.getRequestBody()));
            String json = route(method, ex.getRequestURI().getPath(), query, body);
            send(ex, 200, "application/json; charset=utf-8", json.getBytes("UTF-8"));
        } catch (ApiException e) {
            send(ex, e.status, "application/json; charset=utf-8", ("{\"error\":" + q(e.getMessage()) + "}").getBytes("UTF-8"));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json; charset=utf-8", "{\"error\":\"Server error\"}".getBytes("UTF-8"));
        }
    }

    static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }

    static void serveFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        File root = new File("web").getCanonicalFile();
        File f = new File(root, path).getCanonicalFile();
        if (!f.getPath().startsWith(root.getPath()) || !f.isFile()) {
            send(ex, 404, "text/plain", "Not found".getBytes("UTF-8"));
            return;
        }
        String type = path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "application/javascript" : "text/html";
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        send(ex, 200, type + "; charset=utf-8", out.toByteArray());
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    static Map<String, String> params(String s) throws IOException {
        Map<String, String> m = new HashMap<String, String>();
        if (s == null || s.length() == 0) return m;
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
        }
        return m;
    }

    // ---------------- Routing ----------------

    static synchronized String route(String m, String path, Map<String, String> q, Map<String, String> b) {
        String[] p = path.substring(5).split("/");   // strip "/api/"
        String r = p[0];
        boolean get = m.equals("GET"), post = m.equals("POST"), put = m.equals("PUT");

        if (r.equals("dashboard") && get) return dashboard();
        if (r.equals("demo") && post) return demo();

        if (r.equals("products")) {
            if (get) {
                List<String> l = new ArrayList<String>();
                for (Product x : db.products) l.add(pj(x));
                return arr(l);
            }
            if (post) {
                Product x = new Product(db.nextProductId++, need(b, "name"), need(b, "brand"), need(b, "category"));
                db.products.add(x);
                db.save(DATA_FILE);
                return pj(x);
            }
        }

        if (r.equals("customers")) {
            if (get) {
                List<String> l = new ArrayList<String>();
                for (Customer x : db.customers) l.add(cj(x));
                return arr(l);
            }
            if (post) {
                String phone = need(b, "phone");
                if (!phone.matches("\\d{10}")) throw new ApiException(400, "Phone must be exactly 10 digits.");
                String email = b.get("email") == null ? "" : b.get("email").trim();
                if (email.length() > 0 && !email.matches("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$"))
                    throw new ApiException(400, "Email format is not valid.");
                Customer x = new Customer(db.nextCustomerId++, need(b, "name"), phone, email);
                db.customers.add(x);
                db.save(DATA_FILE);
                return cj(x);
            }
        }

        if (r.equals("warranties")) {
            if (get && p.length > 1 && p[1].equals("lookup")) {
                String serial = q.get("serial") == null ? "" : q.get("serial").trim();
                Warranty w = db.findWarrantyBySerial(serial);
                if (w == null) throw new ApiException(404, "No warranty is registered for serial number " + serial + ".");
                return wj(w);
            }
            if (get) {
                String key = q.get("q") == null ? "" : q.get("q").trim().toLowerCase();
                List<String> l = new ArrayList<String>();
                for (Warranty w : db.warranties) {
                    Product pr = db.findProduct(w.productId);
                    Customer c = db.findCustomer(w.customerId);
                    boolean hit = key.length() == 0 || w.serialNumber.toLowerCase().contains(key)
                            || (pr != null && (pr.name.toLowerCase().contains(key) || pr.brand.toLowerCase().contains(key)))
                            || (c != null && (c.name.toLowerCase().contains(key) || c.phone.contains(key)));
                    if (hit) l.add(wj(w));
                }
                return arr(l);
            }
            if (post) return registerWarranty(b);
        }

        if (r.equals("claims")) {
            if (get) return listClaims(q);
            if (post) return submitClaim(b);
            if (put && p.length > 1) return decideClaim(p[1], b);
        }
        throw new ApiException(404, "Unknown endpoint: " + m + " " + path);
    }

    // ---------------- Business rules (same as the console version) ----------------

    static String registerWarranty(Map<String, String> b) {
        Customer c = db.findCustomer(num(b, "customerId", 1, Integer.MAX_VALUE));
        Product pr = db.findProduct(num(b, "productId", 1, Integer.MAX_VALUE));
        if (c == null) throw new ApiException(400, "Choose an existing customer.");
        if (pr == null) throw new ApiException(400, "Choose an existing product.");
        String serial = need(b, "serialNumber").toUpperCase();
        if (db.findWarrantyBySerial(serial) != null) throw new ApiException(409, "Serial number " + serial + " is already registered.");
        Date date = Util.parse(need(b, "purchaseDate"));
        if (date == null) throw new ApiException(400, "Purchase date must look like 2026-03-15.");
        if (date.after(Util.today())) throw new ApiException(400, "Purchase date cannot be in the future.");
        Warranty w = new Warranty(db.nextWarrantyId++, c.id, pr.id, serial, date, num(b, "durationMonths", 1, 120));
        db.warranties.add(w);
        db.save(DATA_FILE);
        return wj(w);
    }

    static String submitClaim(Map<String, String> b) {
        Warranty w = db.findWarrantyBySerial(need(b, "serialNumber"));
        if (w == null) throw new ApiException(404, "No warranty is registered for that serial number.");
        if (!w.isActive()) throw new ApiException(422, "Claim not accepted: this warranty expired on " + Util.fmt(w.getExpiryDate()) + ".");
        if (db.hasPendingClaim(w.id)) throw new ApiException(409, "A claim for this product is already pending.");
        Claim cl = new Claim(db.nextClaimId++, w.id, need(b, "issue"), Util.today());
        db.claims.add(cl);
        db.save(DATA_FILE);
        return cj2(cl);
    }

    static String decideClaim(String idText, Map<String, String> b) {
        Claim cl;
        try { cl = db.findClaim(Integer.parseInt(idText)); } catch (NumberFormatException e) { cl = null; }
        if (cl == null) throw new ApiException(404, "Claim not found.");
        if (cl.status != ClaimStatus.PENDING) throw new ApiException(409, "This claim is already " + cl.status + " and cannot be changed.");
        String s = need(b, "status");
        if (s.equalsIgnoreCase("Approved")) cl.status = ClaimStatus.APPROVED;
        else if (s.equalsIgnoreCase("Rejected")) cl.status = ClaimStatus.REJECTED;
        else throw new ApiException(400, "Status must be Approved or Rejected.");
        cl.remarks = b.get("remarks") == null ? "" : b.get("remarks").trim();
        cl.resolvedOn = Util.today();
        db.save(DATA_FILE);
        return cj2(cl);
    }

    static String listClaims(Map<String, String> q) {
        String st = q.get("status") == null ? "All" : q.get("status");
        String key = q.get("q") == null ? "" : q.get("q").trim().toLowerCase();
        List<String> l = new ArrayList<String>();
        for (int i = db.claims.size() - 1; i >= 0; i--) {   // newest first
            Claim cl = db.claims.get(i);
            if (!st.equals("All") && !cl.status.toString().equalsIgnoreCase(st)) continue;
            Warranty w = db.findWarranty(cl.warrantyId);
            Product pr = w == null ? null : db.findProduct(w.productId);
            Customer c = w == null ? null : db.findCustomer(w.customerId);
            String hay = ((w == null ? "" : w.serialNumber) + " " + (pr == null ? "" : pr.brand + " " + pr.name)
                    + " " + (c == null ? "" : c.name)).toLowerCase();
            if (key.length() > 0 && !hay.contains(key)) continue;
            l.add(cj2(cl));
        }
        return arr(l);
    }

    static String dashboard() {
        int active = 0, expired = 0, soon = 0, pend = 0, appr = 0, rej = 0;
        for (Warranty w : db.warranties) {
            if (w.isActive()) { active++; if (w.daysLeft() <= 30) soon++; } else expired++;
        }
        for (Claim c : db.claims) {
            if (c.status == ClaimStatus.PENDING) pend++;
            else if (c.status == ClaimStatus.APPROVED) appr++;
            else rej++;
        }
        return "{\"products\":" + db.products.size() + ",\"customers\":" + db.customers.size()
                + ",\"active\":" + active + ",\"expired\":" + expired + ",\"expiringSoon\":" + soon
                + ",\"pending\":" + pend + ",\"approved\":" + appr + ",\"rejected\":" + rej + "}";
    }

    static String demo() {
        if (!db.products.isEmpty() || !db.customers.isEmpty()) throw new ApiException(409, "Demo data only loads into an empty database.");
        Product p1 = new Product(db.nextProductId++, "Bravia 55 inch 4K TV", "Sony", "TV");
        Product p2 = new Product(db.nextProductId++, "Galaxy S24", "Samsung", "Phone");
        Product p3 = new Product(db.nextProductId++, "Inspiron 15", "Dell", "Laptop");
        db.products.add(p1); db.products.add(p2); db.products.add(p3);
        Customer c1 = new Customer(db.nextCustomerId++, "Ravi Kumar", "9876543210", "ravi@example.com");
        Customer c2 = new Customer(db.nextCustomerId++, "Anitha Reddy", "9123456780", "");
        db.customers.add(c1); db.customers.add(c2);
        Date t = Util.today();
        Warranty w1 = new Warranty(db.nextWarrantyId++, c1.id, p1.id, "SN-TV-1001", Util.addMonths(t, -3), 24);
        Warranty w2 = new Warranty(db.nextWarrantyId++, c2.id, p2.id, "SN-PH-2002", Util.addMonths(t, -14), 12);
        Warranty w3 = new Warranty(db.nextWarrantyId++, c1.id, p3.id, "SN-LP-3003", Util.addMonths(t, -5), 12);
        db.warranties.add(w1); db.warranties.add(w2); db.warranties.add(w3);
        db.claims.add(new Claim(db.nextClaimId++, w3.id, "Battery drains within 30 minutes", Util.addDays(t, -2)));
        db.save(DATA_FILE);
        return "{\"ok\":true}";
    }

    // ---------------- JSON helpers ----------------

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c < 32) sb.append(' ');
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static String arr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(items.get(i));
        }
        return sb.append(']').toString();
    }

    static String need(Map<String, String> b, String k) {
        String v = b.get(k);
        if (v == null || v.trim().length() == 0) throw new ApiException(400, "Please fill in: " + k);
        return v.trim();
    }

    static int num(Map<String, String> b, String k, int min, int max) {
        try {
            int v = Integer.parseInt(need(b, k));
            if (v >= min && v <= max) return v;
        } catch (NumberFormatException e) { }
        throw new ApiException(400, k + " must be a number" + (max == Integer.MAX_VALUE ? "." : " from " + min + " to " + max + "."));
    }

    static String pj(Product x) {
        return "{\"id\":" + x.id + ",\"name\":" + q(x.name) + ",\"brand\":" + q(x.brand) + ",\"category\":" + q(x.category) + "}";
    }

    static String cj(Customer x) {
        return "{\"id\":" + x.id + ",\"name\":" + q(x.name) + ",\"phone\":" + q(x.phone) + ",\"email\":" + q(x.email) + "}";
    }

    static String wj(Warranty w) {
        Product pr = db.findProduct(w.productId);
        Customer c = db.findCustomer(w.customerId);
        return "{\"id\":" + w.id + ",\"serial\":" + q(w.serialNumber)
                + ",\"product\":" + q(pr == null ? "?" : pr.brand + " " + pr.name)
                + ",\"customer\":" + q(c == null ? "?" : c.name)
                + ",\"phone\":" + q(c == null ? "" : c.phone)
                + ",\"purchased\":" + q(Util.fmt(w.purchaseDate)) + ",\"expires\":" + q(Util.fmt(w.getExpiryDate()))
                + ",\"months\":" + w.durationMonths + ",\"status\":" + q(w.getStatus()) + ",\"daysLeft\":" + w.daysLeft() + "}";
    }

    static String cj2(Claim cl) {
        Warranty w = db.findWarranty(cl.warrantyId);
        Product pr = w == null ? null : db.findProduct(w.productId);
        Customer c = w == null ? null : db.findCustomer(w.customerId);
        return "{\"id\":" + cl.id + ",\"serial\":" + q(w == null ? "?" : w.serialNumber)
                + ",\"product\":" + q(pr == null ? "?" : pr.brand + " " + pr.name)
                + ",\"customer\":" + q(c == null ? "?" : c.name)
                + ",\"issue\":" + q(cl.issue) + ",\"filed\":" + q(Util.fmt(cl.filedOn))
                + ",\"status\":" + q(cl.status.toString()) + ",\"remarks\":" + q(cl.remarks) + "}";
    }
}
