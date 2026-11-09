import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/*
 * ============================================================
 *  PRODUCT WARRANTY MANAGEMENT SYSTEM  (Problem Statement 29)
 *  Electronics shop: register warranties, track customer claims
 * ============================================================
 *  Works on old Java versions too (no lambdas / streams / java.time).
 *  Compile : javac WarrantyManagementSystem.java   (any file name works)
 *  Run     : java WarrantyManagementSystem
 *  Data is saved automatically to warranty_data.ser
 */

// ------------------------------------------------------------
//  UTILITIES
// ------------------------------------------------------------

class Util {
    static final String PATTERN = "yyyy-MM-dd";

    static Date today() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    static Date addMonths(Date d, int months) {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.add(Calendar.MONTH, months);
        return c.getTime();
    }

    static Date addDays(Date d, int days) {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }

    static String fmt(Date d) {
        return d == null ? "-" : new SimpleDateFormat(PATTERN).format(d);
    }

    /** Returns null if the text is not a valid yyyy-MM-dd date. */
    static Date parse(String s) {
        if (!s.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        SimpleDateFormat f = new SimpleDateFormat(PATTERN);
        f.setLenient(false);
        try {
            return f.parse(s);
        } catch (ParseException e) {
            return null;
        }
    }

    static long daysBetween(Date from, Date to) {
        return Math.round((to.getTime() - from.getTime()) / (1000.0 * 60 * 60 * 24));
    }
}

// ------------------------------------------------------------
//  MODEL CLASSES
// ------------------------------------------------------------

enum ClaimStatus {
    PENDING("Pending"), APPROVED("Approved"), REJECTED("Rejected");

    private final String label;

    ClaimStatus(String label) { this.label = label; }

    public String toString() { return label; }
}

class Product implements Serializable {
    private static final long serialVersionUID = 1L;
    final int id;
    String name;
    String brand;
    String category;

    Product(int id, String name, String brand, String category) {
        this.id = id;
        this.name = name;
        this.brand = brand;
        this.category = category;
    }
}

class Customer implements Serializable {
    private static final long serialVersionUID = 1L;
    final int id;
    String name;
    String phone;
    String email;

    Customer(int id, String name, String phone, String email) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.email = email;
    }
}

class Warranty implements Serializable {
    private static final long serialVersionUID = 1L;
    final int id;
    final int customerId;
    final int productId;
    final String serialNumber;
    final Date purchaseDate;
    final int durationMonths;

    Warranty(int id, int customerId, int productId, String serialNumber,
             Date purchaseDate, int durationMonths) {
        this.id = id;
        this.customerId = customerId;
        this.productId = productId;
        this.serialNumber = serialNumber;
        this.purchaseDate = purchaseDate;
        this.durationMonths = durationMonths;
    }

    Date getExpiryDate() { return Util.addMonths(purchaseDate, durationMonths); }

    boolean isActive() { return !Util.today().after(getExpiryDate()); }

    String getStatus() { return isActive() ? "Active" : "Expired"; }

    long daysLeft() { return Util.daysBetween(Util.today(), getExpiryDate()); }
}

class Claim implements Serializable {
    private static final long serialVersionUID = 1L;
    final int id;
    final int warrantyId;
    final String issue;
    final Date filedOn;
    ClaimStatus status = ClaimStatus.PENDING;
    String remarks = "";
    Date resolvedOn;

    Claim(int id, int warrantyId, String issue, Date filedOn) {
        this.id = id;
        this.warrantyId = warrantyId;
        this.issue = issue;
        this.filedOn = filedOn;
    }
}

// ------------------------------------------------------------
//  DATA STORE (in-memory lists + file persistence)
// ------------------------------------------------------------

class DataStore implements Serializable {
    private static final long serialVersionUID = 1L;

    final List<Product> products = new ArrayList<Product>();
    final List<Customer> customers = new ArrayList<Customer>();
    final List<Warranty> warranties = new ArrayList<Warranty>();
    final List<Claim> claims = new ArrayList<Claim>();

    int nextProductId = 1, nextCustomerId = 1, nextWarrantyId = 1, nextClaimId = 1;

    Product findProduct(int id) {
        for (Product p : products) if (p.id == id) return p;
        return null;
    }

    Customer findCustomer(int id) {
        for (Customer c : customers) if (c.id == id) return c;
        return null;
    }

    Warranty findWarranty(int id) {
        for (Warranty w : warranties) if (w.id == id) return w;
        return null;
    }

    Warranty findWarrantyBySerial(String serial) {
        for (Warranty w : warranties) if (w.serialNumber.equalsIgnoreCase(serial)) return w;
        return null;
    }

    Claim findClaim(int id) {
        for (Claim c : claims) if (c.id == id) return c;
        return null;
    }

    boolean hasPendingClaim(int warrantyId) {
        for (Claim c : claims) {
            if (c.warrantyId == warrantyId && c.status == ClaimStatus.PENDING) return true;
        }
        return false;
    }

    static DataStore load(String file) {
        File f = new File(file);
        if (!f.exists()) return new DataStore();
        ObjectInputStream ois = null;
        try {
            ois = new ObjectInputStream(new FileInputStream(f));
            return (DataStore) ois.readObject();
        } catch (Exception e) {
            System.out.println("[!] Could not read saved data (" + e.getMessage() + "). Starting fresh.");
            return new DataStore();
        } finally {
            if (ois != null) {
                try { ois.close(); } catch (IOException ignored) { }
            }
        }
    }

    void save(String file) {
        ObjectOutputStream oos = null;
        try {
            oos = new ObjectOutputStream(new FileOutputStream(file));
            oos.writeObject(this);
        } catch (IOException e) {
            System.out.println("[!] Could not save data: " + e.getMessage());
        } finally {
            if (oos != null) {
                try { oos.close(); } catch (IOException ignored) { }
            }
        }
    }
}

// ------------------------------------------------------------
//  MAIN APPLICATION
// ------------------------------------------------------------

class WarrantyManagementSystem {

    private static final String DATA_FILE = "warranty_data.ser";
    private static final Scanner in = new Scanner(System.in);
    private static DataStore db;

    public static void main(String[] args) {
        db = DataStore.load(DATA_FILE);
        System.out.println("\n=== PRODUCT WARRANTY MANAGEMENT SYSTEM ===");

        boolean running = true;
        while (running) {
            printMenu();
            int choice = readInt("Choose an option: ", 0, 11);
            System.out.println();
            switch (choice) {
                case 1:  addProduct(); break;
                case 2:  listProducts(); break;
                case 3:  addCustomer(); break;
                case 4:  listCustomers(); break;
                case 5:  registerWarranty(); break;
                case 6:  printWarranties(db.warranties, "ALL WARRANTIES"); break;
                case 7:  searchWarranties(); break;
                case 8:  submitClaim(); break;
                case 9:  updateClaimStatus(); break;
                case 10: viewClaims(); break;
                case 11: loadDemoData(); break;
                case 0:  running = false; break;
                default: break;
            }
        }
        db.save(DATA_FILE);
        System.out.println("Data saved. Goodbye!");
    }

    private static void printMenu() {
        System.out.println("\n-------------------- MENU --------------------");
        System.out.println(" 1. Add product            2. View products");
        System.out.println(" 3. Add customer           4. View customers");
        System.out.println(" 5. Register warranty      6. View all warranties (Active/Expired)");
        System.out.println(" 7. Search warranties      8. Submit warranty claim");
        System.out.println(" 9. Update claim status   10. View / filter claims");
        System.out.println("11. Load demo data         0. Save & exit");
        System.out.println("----------------------------------------------");
    }

    // -------------------- PRODUCTS --------------------

    private static void addProduct() {
        System.out.println("--- Add Product ---");
        String name = readString("Product name: ", true);
        String brand = readString("Brand: ", true);
        String category = readString("Category (e.g. TV, Laptop, Phone): ", true);
        Product p = new Product(db.nextProductId++, name, brand, category);
        db.products.add(p);
        db.save(DATA_FILE);
        System.out.println("Product added with ID " + p.id);
    }

    private static void listProducts() {
        if (db.products.isEmpty()) { System.out.println("No products found."); return; }
        System.out.println("--- PRODUCTS ---");
        System.out.printf("%-5s %-25s %-15s %-15s%n", "ID", "Name", "Brand", "Category");
        System.out.println(line(62));
        for (Product p : db.products) {
            System.out.printf("%-5d %-25s %-15s %-15s%n",
                    p.id, trunc(p.name, 25), trunc(p.brand, 15), trunc(p.category, 15));
        }
    }

    // -------------------- CUSTOMERS --------------------

    private static void addCustomer() {
        System.out.println("--- Add Customer ---");
        String name = readString("Customer name: ", true);
        String phone;
        while (true) {
            phone = readString("Phone (10 digits): ", true);
            if (phone.matches("\\d{10}")) break;
            System.out.println("Invalid phone number. Enter exactly 10 digits.");
        }
        String email;
        while (true) {
            email = readString("Email (optional, press Enter to skip): ", false);
            if (email.length() == 0 || email.matches("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$")) break;
            System.out.println("Invalid email format.");
        }
        Customer c = new Customer(db.nextCustomerId++, name, phone, email);
        db.customers.add(c);
        db.save(DATA_FILE);
        System.out.println("Customer added with ID " + c.id);
    }

    private static void listCustomers() {
        if (db.customers.isEmpty()) { System.out.println("No customers found."); return; }
        System.out.println("--- CUSTOMERS ---");
        System.out.printf("%-5s %-22s %-13s %-28s%n", "ID", "Name", "Phone", "Email");
        System.out.println(line(70));
        for (Customer c : db.customers) {
            System.out.printf("%-5d %-22s %-13s %-28s%n",
                    c.id, trunc(c.name, 22), c.phone, trunc(c.email.length() == 0 ? "-" : c.email, 28));
        }
    }

    // -------------------- WARRANTY REGISTRATION --------------------

    private static void registerWarranty() {
        System.out.println("--- Register Warranty ---");
        if (db.products.isEmpty() || db.customers.isEmpty()) {
            System.out.println("Add at least one product and one customer first.");
            return;
        }

        listCustomers();
        Customer customer = null;
        while (customer == null) {
            customer = db.findCustomer(readInt("Enter Customer ID: ", 1, Integer.MAX_VALUE));
            if (customer == null) System.out.println("No customer with that ID.");
        }

        listProducts();
        Product product = null;
        while (product == null) {
            product = db.findProduct(readInt("Enter Product ID: ", 1, Integer.MAX_VALUE));
            if (product == null) System.out.println("No product with that ID.");
        }

        String serial;
        while (true) {
            serial = readString("Serial number: ", true).toUpperCase();
            if (db.findWarrantyBySerial(serial) == null) break;
            System.out.println("This serial number is already registered.");
        }

        Date purchaseDate = readDate("Purchase date (yyyy-MM-dd): ");
        int months = readInt("Warranty duration in months (1-120): ", 1, 120);

        Warranty w = new Warranty(db.nextWarrantyId++, customer.id, product.id,
                serial, purchaseDate, months);
        db.warranties.add(w);
        db.save(DATA_FILE);

        System.out.println("\nWarranty registered successfully!");
        System.out.println("  Warranty ID : " + w.id);
        System.out.println("  Product     : " + product.brand + " " + product.name);
        System.out.println("  Customer    : " + customer.name);
        System.out.println("  Valid until : " + Util.fmt(w.getExpiryDate()));
        System.out.println("  Status      : " + w.getStatus());
    }

    // -------------------- VIEW / SEARCH WARRANTIES --------------------

    private static void printWarranties(List<Warranty> list, String title) {
        if (list.isEmpty()) { System.out.println("No warranties found."); return; }
        System.out.println("--- " + title + " (" + list.size() + ") ---");
        System.out.printf("%-4s %-16s %-24s %-16s %-11s %-11s %-5s %-8s %-9s%n",
                "ID", "Serial No.", "Product", "Customer", "Purchased", "Expires", "Mths", "Status", "Days Left");
        System.out.println(line(112));
        for (Warranty w : list) {
            Product p = db.findProduct(w.productId);
            Customer c = db.findCustomer(w.customerId);
            String daysLeft = w.isActive() ? String.valueOf(w.daysLeft()) : "-";
            System.out.printf("%-4d %-16s %-24s %-16s %-11s %-11s %-5d %-8s %-9s%n",
                    w.id, trunc(w.serialNumber, 16),
                    trunc(p == null ? "?" : p.brand + " " + p.name, 24),
                    trunc(c == null ? "?" : c.name, 16),
                    Util.fmt(w.purchaseDate), Util.fmt(w.getExpiryDate()),
                    w.durationMonths, w.getStatus(), daysLeft);
        }
    }

    private static void searchWarranties() {
        System.out.println("--- Search Warranties ---");
        System.out.println("Search by serial number, product name/brand, or customer name/phone.");
        String key = readString("Keyword: ", true).toLowerCase();

        List<Warranty> result = new ArrayList<Warranty>();
        for (Warranty w : db.warranties) {
            Product p = db.findProduct(w.productId);
            Customer c = db.findCustomer(w.customerId);
            boolean match = w.serialNumber.toLowerCase().contains(key)
                    || (p != null && (p.name.toLowerCase().contains(key)
                    || p.brand.toLowerCase().contains(key)))
                    || (c != null && (c.name.toLowerCase().contains(key)
                    || c.phone.contains(key)));
            if (match) result.add(w);
        }
        printWarranties(result, "SEARCH RESULTS");
    }

    // -------------------- CLAIMS --------------------

    private static void submitClaim() {
        System.out.println("--- Submit Warranty Claim ---");
        if (db.warranties.isEmpty()) { System.out.println("No registered warranties yet."); return; }

        String serial = readString("Enter product serial number: ", true);
        Warranty w = db.findWarrantyBySerial(serial);
        if (w == null) {
            System.out.println("No warranty registered for serial number '" + serial + "'.");
            return;
        }

        Product p = db.findProduct(w.productId);
        Customer c = db.findCustomer(w.customerId);
        System.out.println("Product  : " + (p == null ? "?" : p.brand + " " + p.name));
        System.out.println("Customer : " + (c == null ? "?" : c.name));
        System.out.println("Warranty : " + w.getStatus() + " (expires " + Util.fmt(w.getExpiryDate()) + ")");

        // Eligibility rule: claims only for ACTIVE warranties
        if (!w.isActive()) {
            System.out.println("\nCLAIM NOT ACCEPTED: this warranty expired on " + Util.fmt(w.getExpiryDate()) + ".");
            return;
        }
        if (db.hasPendingClaim(w.id)) {
            System.out.println("\nA claim for this product is already pending. Please wait for it to be resolved.");
            return;
        }

        String issue = readString("Describe the issue: ", true);
        Claim claim = new Claim(db.nextClaimId++, w.id, issue, Util.today());
        db.claims.add(claim);
        db.save(DATA_FILE);
        System.out.println("\nClaim submitted successfully! Claim ID: " + claim.id + " | Status: " + claim.status);
    }

    private static void updateClaimStatus() {
        System.out.println("--- Update Claim Status ---");
        List<Claim> pending = new ArrayList<Claim>();
        for (Claim c : db.claims) {
            if (c.status == ClaimStatus.PENDING) pending.add(c);
        }
        if (pending.isEmpty()) { System.out.println("No pending claims to process."); return; }

        printClaims(pending, "PENDING CLAIMS");
        Claim claim = null;
        while (claim == null) {
            int id = readInt("Enter Claim ID to update (0 to cancel): ", 0, Integer.MAX_VALUE);
            if (id == 0) return;
            Claim found = db.findClaim(id);
            if (found == null) {
                System.out.println("No claim with that ID.");
            } else if (found.status != ClaimStatus.PENDING) {
                System.out.println("This claim is already " + found.status + " and cannot be changed.");
            } else {
                claim = found;
            }
        }

        System.out.println("1. Approve   2. Reject");
        int decision = readInt("Decision: ", 1, 2);
        claim.status = (decision == 1) ? ClaimStatus.APPROVED : ClaimStatus.REJECTED;
        claim.remarks = readString("Remarks (optional): ", false);
        claim.resolvedOn = Util.today();
        db.save(DATA_FILE);
        System.out.println("Claim " + claim.id + " marked as " + claim.status + ".");
    }

    private static void viewClaims() {
        System.out.println("--- View / Filter Claims ---");
        if (db.claims.isEmpty()) { System.out.println("No claims submitted yet."); return; }

        System.out.println("1. All   2. Pending   3. Approved   4. Rejected");
        int f = readInt("Filter by status: ", 1, 4);
        String serialKey = readString("Filter by serial number (Enter to skip): ", false).toLowerCase();

        List<Claim> result = new ArrayList<Claim>();
        for (Claim c : db.claims) {
            boolean statusOk = f == 1
                    || (f == 2 && c.status == ClaimStatus.PENDING)
                    || (f == 3 && c.status == ClaimStatus.APPROVED)
                    || (f == 4 && c.status == ClaimStatus.REJECTED);
            Warranty w = db.findWarranty(c.warrantyId);
            boolean serialOk = serialKey.length() == 0
                    || (w != null && w.serialNumber.toLowerCase().contains(serialKey));
            if (statusOk && serialOk) result.add(c);
        }

        printClaims(result, "CLAIMS");

        int p = 0, a = 0, r = 0;
        for (Claim c : db.claims) {
            if (c.status == ClaimStatus.PENDING) p++;
            else if (c.status == ClaimStatus.APPROVED) a++;
            else r++;
        }
        System.out.println("\nSummary -> Pending: " + p + " | Approved: " + a + " | Rejected: " + r);
    }

    private static void printClaims(List<Claim> list, String title) {
        if (list.isEmpty()) { System.out.println("No claims match."); return; }
        System.out.println("--- " + title + " (" + list.size() + ") ---");
        System.out.printf("%-4s %-15s %-20s %-11s %-9s %-26s %-18s%n",
                "ID", "Serial No.", "Customer", "Filed On", "Status", "Issue", "Remarks");
        System.out.println(line(110));
        for (Claim cl : list) {
            Warranty w = db.findWarranty(cl.warrantyId);
            Customer c = (w == null) ? null : db.findCustomer(w.customerId);
            System.out.printf("%-4d %-15s %-20s %-11s %-9s %-26s %-18s%n",
                    cl.id, trunc(w == null ? "?" : w.serialNumber, 15),
                    trunc(c == null ? "?" : c.name, 20), Util.fmt(cl.filedOn), cl.status,
                    trunc(cl.issue, 26), trunc(cl.remarks.length() == 0 ? "-" : cl.remarks, 18));
        }
    }

    // -------------------- DEMO DATA --------------------

    private static void loadDemoData() {
        if (!db.products.isEmpty() || !db.customers.isEmpty()) {
            System.out.println("Demo data can only be loaded into an empty database.");
            return;
        }
        Product p1 = new Product(db.nextProductId++, "Bravia 55 inch 4K TV", "Sony", "TV");
        Product p2 = new Product(db.nextProductId++, "Galaxy S24", "Samsung", "Phone");
        Product p3 = new Product(db.nextProductId++, "Inspiron 15", "Dell", "Laptop");
        db.products.add(p1);
        db.products.add(p2);
        db.products.add(p3);

        Customer c1 = new Customer(db.nextCustomerId++, "Ravi Kumar", "9876543210", "ravi@example.com");
        Customer c2 = new Customer(db.nextCustomerId++, "Anitha Reddy", "9123456780", "");
        db.customers.add(c1);
        db.customers.add(c2);

        Date today = Util.today();
        Warranty w1 = new Warranty(db.nextWarrantyId++, c1.id, p1.id, "SN-TV-1001", Util.addMonths(today, -3), 24);
        Warranty w2 = new Warranty(db.nextWarrantyId++, c2.id, p2.id, "SN-PH-2002", Util.addMonths(today, -14), 12);
        Warranty w3 = new Warranty(db.nextWarrantyId++, c1.id, p3.id, "SN-LP-3003", Util.addMonths(today, -5), 12);
        db.warranties.add(w1);
        db.warranties.add(w2);
        db.warranties.add(w3);

        Claim cl = new Claim(db.nextClaimId++, w3.id, "Laptop battery drains within 30 minutes", Util.addDays(today, -2));
        db.claims.add(cl);

        db.save(DATA_FILE);
        System.out.println("Demo data loaded: 3 products, 2 customers, 3 warranties (1 expired), 1 claim.");
        System.out.println("Try submitting a claim for SN-PH-2002 to see the expired-warranty rejection.");
    }

    // -------------------- INPUT / FORMAT HELPERS --------------------

    private static String readString(String prompt, boolean required) {
        while (true) {
            System.out.print(prompt);
            String s = in.hasNextLine() ? in.nextLine().trim() : "";
            if (s.length() > 0 || !required) return s;
            System.out.println("This field cannot be empty.");
        }
    }

    private static int readInt(String prompt, int min, int max) {
        while (true) {
            System.out.print(prompt);
            String s = in.hasNextLine() ? in.nextLine().trim() : "0";
            try {
                int v = Integer.parseInt(s);
                if (v >= min && v <= max) return v;
                System.out.println("Please enter a number between " + min + " and "
                        + (max == Integer.MAX_VALUE ? "a valid ID" : String.valueOf(max)) + ".");
            } catch (NumberFormatException e) {
                System.out.println("Invalid number. Try again.");
            }
        }
    }

    /** Reads a purchase date (yyyy-MM-dd) that is not in the future. */
    private static Date readDate(String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = in.hasNextLine() ? in.nextLine().trim() : "";
            Date d = Util.parse(s);
            if (d == null) {
                System.out.println("Invalid date. Use format yyyy-MM-dd (e.g. 2026-03-15).");
            } else if (d.after(Util.today())) {
                System.out.println("Purchase date cannot be in the future.");
            } else {
                return d;
            }
        }
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }

    private static String line(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('-');
        return sb.toString();
    }
}
