// Address of the Java server (WarrantyServer.java). Change it if you use another host or port.
const API = "http://localhost:8080/api";

const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const today = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);
const stamp = (s) => `<span class="stamp ${s.toLowerCase()}">${s}</span>`;
const opts = (list, label) => list.map((x) => `<option value="${x.id}">${esc(label(x))}</option>`).join("");
const daysText = (w) => (w.status === "Active" ? `${w.daysLeft} days left` : `Expired ${-w.daysLeft} days ago`);
const views = {};

async function api(path, method = "GET", data) {
  const opt = { method };
  if (data) {
    opt.headers = { "Content-Type": "application/x-www-form-urlencoded" };
    opt.body = new URLSearchParams(data).toString();
  }
  let res;
  try {
    res = await fetch(API + path, opt);
  } catch {
    throw new Error("Cannot reach the Java server. Start WarrantyServer and check that it is running on " + API.replace("/api", "") + ".");
  }
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error || "Request failed (" + res.status + ")");
  return json;
}

function toast(msg, bad) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = "show" + (bad ? " bad" : "");
  clearTimeout(toast.id);
  toast.id = setTimeout(() => (t.className = ""), 3500);
}

function table(cols, rows, empty) {
  if (!rows.length) return `<p class="empty">${empty}</p>`;
  const head = cols.map((c) => `<th>${c}</th>`).join("");
  const body = rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("");
  return `<div class="tw"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
}

function bind(id, fn) {
  const f = $("#" + id);
  f.addEventListener("submit", async (e) => {
    e.preventDefault();
    const btn = f.querySelector("button");
    btn.disabled = true;
    try {
      await fn(Object.fromEntries(new FormData(f)), f);
    } catch (err) {
      toast(err.message, true);
    } finally {
      btn.disabled = false;
    }
  });
}

function debounce(fn, ms = 250) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

async function show(view) {
  document.querySelectorAll("nav button").forEach((b) => b.classList.toggle("on", b.dataset.view === view));
  const el = $("#app");
  el.innerHTML = '<p class="empty">Loading…</p>';
  try {
    await views[view](el);
    $("#conn").textContent = "Server connected";
    $("#conn").className = "";
  } catch (e) {
    el.innerHTML = `<div class="panel err"><b>Can't load this page</b><p>${esc(e.message)}</p></div>`;
    $("#conn").textContent = "Server offline";
    $("#conn").className = "bad";
  }
}

// ---------------- Dashboard ----------------
views.dashboard = async (el) => {
  const [d, ws] = await Promise.all([api("/dashboard"), api("/warranties")]);
  const soon = ws.filter((w) => w.status === "Active" && w.daysLeft <= 30).sort((a, b) => a.daysLeft - b.daysLeft);
  const stats = [["Active warranties", d.active, "ok"], ["Expired warranties", d.expired, "bad"], ["Pending claims", d.pending, "warn"], ["Expiring within 30 days", d.expiringSoon, "warn"]];
  el.innerHTML = `<h2>Dashboard</h2>
    <div class="stats">${stats.map(([l, n, c]) => `<div class="stat ${c}"><b>${n}</b><span>${l}</span></div>`).join("")}</div>
    <h3>Expiring soon</h3>
    ${table(["Serial", "Product", "Customer", "Expires", "Time left"], soon.map((w) => [esc(w.serial), esc(w.product), esc(w.customer), w.expires, daysText(w)]), "No warranties expire in the next 30 days.")}
    ${d.products === 0 ? `<p class="empty">Nothing is registered yet. <button id="demo">Load demo data</button></p>` : ""}`;
  const demo = $("#demo");
  if (demo) demo.onclick = async () => {
    try { await api("/demo", "POST"); toast("Demo data loaded"); show("dashboard"); } catch (e) { toast(e.message, true); }
  };
};

// ---------------- Products ----------------
views.products = async (el) => {
  const ps = await api("/products");
  el.innerHTML = `<h2>Products</h2>
    <form id="f" class="panel grid">
      <label>Product name<input name="name" required></label>
      <label>Brand<input name="brand" required></label>
      <label>Category<input name="category" placeholder="TV, Laptop, Phone" required></label>
      <button>Add product</button>
    </form>
    ${table(["ID", "Name", "Brand", "Category"], ps.map((p) => [p.id, esc(p.name), esc(p.brand), esc(p.category)]), "No products yet. Add the first one above.")}`;
  bind("f", async (d) => { await api("/products", "POST", d); toast("Product added"); show("products"); });
};

// ---------------- Customers ----------------
views.customers = async (el) => {
  const cs = await api("/customers");
  el.innerHTML = `<h2>Customers</h2>
    <form id="f" class="panel grid">
      <label>Customer name<input name="name" required></label>
      <label>Phone<input name="phone" pattern="[0-9]{10}" title="10 digits" placeholder="10 digits" required></label>
      <label>Email (optional)<input name="email" type="email"></label>
      <button>Add customer</button>
    </form>
    ${table(["ID", "Name", "Phone", "Email"], cs.map((c) => [c.id, esc(c.name), esc(c.phone), esc(c.email || "-")]), "No customers yet. Add the first one above.")}`;
  bind("f", async (d) => { await api("/customers", "POST", d); toast("Customer added"); show("customers"); });
};

// ---------------- Warranties ----------------
views.warranties = async (el) => {
  const [cs, ps] = await Promise.all([api("/customers"), api("/products")]);
  el.innerHTML = `<h2>Warranties</h2>
    <form id="f" class="panel grid">
      <label>Customer<select name="customerId" required>${opts(cs, (c) => `${c.name} (${c.phone})`)}</select></label>
      <label>Product<select name="productId" required>${opts(ps, (p) => `${p.brand} ${p.name}`)}</select></label>
      <label>Serial number<input name="serialNumber" required></label>
      <label>Purchase date<input type="date" name="purchaseDate" max="${today()}" required></label>
      <label>Duration (months)<input type="number" name="durationMonths" min="1" max="120" value="12" required></label>
      <button>Register warranty</button>
    </form>
    ${cs.length && ps.length ? "" : '<p class="empty">Add at least one customer and one product before registering a warranty.</p>'}
    <div class="bar"><input id="q" placeholder="Search by serial number, product, customer or phone"></div>
    <div id="list"></div>`;
  const load = async () => {
    const ws = await api("/warranties?q=" + encodeURIComponent($("#q").value));
    $("#list").innerHTML = table(
      ["ID", "Serial", "Product", "Customer", "Purchased", "Expires", "Status", "Time left"],
      ws.map((w) => [w.id, esc(w.serial), esc(w.product), `${esc(w.customer)}<br><small>${esc(w.phone)}</small>`, w.purchased, w.expires, stamp(w.status), daysText(w)]),
      "No warranties match."
    );
  };
  $("#q").addEventListener("input", debounce(() => load().catch((e) => toast(e.message, true))));
  bind("f", async (d, f) => { const w = await api("/warranties", "POST", d); toast("Warranty registered: " + w.status); f.reset(); await load(); });
  await load();
};

// ---------------- Claims ----------------
views.claims = async (el) => {
  el.innerHTML = `<h2>Claims</h2>
    <form id="f" class="panel grid">
      <label>Serial number<input name="serialNumber" id="sn" required></label>
      <p id="pv" class="hint"></p>
      <label class="wide">Describe the issue<textarea name="issue" rows="2" required></textarea></label>
      <button>Submit claim</button>
    </form>
    <div class="bar">
      <select id="st"><option>All</option><option>Pending</option><option>Approved</option><option>Rejected</option></select>
      <input id="q" placeholder="Search by serial number, customer or product">
    </div>
    <div id="list"></div>`;
  const load = async () => {
    const cl = await api(`/claims?status=${$("#st").value}&q=${encodeURIComponent($("#q").value)}`);
    $("#list").innerHTML = table(
      ["ID", "Serial", "Product and customer", "Filed", "Issue", "Status", "Remarks", ""],
      cl.map((c) => [c.id, esc(c.serial), `${esc(c.product)}<br><small>${esc(c.customer)}</small>`, c.filed, esc(c.issue), stamp(c.status), esc(c.remarks || "-"),
        c.status === "Pending" ? `<button class="ok" data-id="${c.id}" data-s="Approved">Approve</button> <button class="no" data-id="${c.id}" data-s="Rejected">Reject</button>` : ""]),
      "No claims match. Submit one above, or change the filter."
    );
  };
  $("#sn").addEventListener("change", async () => {
    const pv = $("#pv");
    try {
      const w = await api("/warranties/lookup?serial=" + encodeURIComponent($("#sn").value));
      pv.innerHTML = `${esc(w.product)} for ${esc(w.customer)} ${stamp(w.status)} ${w.status === "Active" ? "" : "Expired warranties can't be claimed."}`;
    } catch (e) { pv.textContent = e.message; }
  });
  $("#st").addEventListener("change", () => load().catch((e) => toast(e.message, true)));
  $("#q").addEventListener("input", debounce(() => load().catch((e) => toast(e.message, true))));
  $("#list").addEventListener("click", async (e) => {
    const b = e.target.closest("button[data-id]");
    if (!b) return;
    const remarks = prompt(`${b.dataset.s} claim ${b.dataset.id}. Add remarks (optional):`, "");
    if (remarks === null) return;
    try {
      await api("/claims/" + b.dataset.id, "PUT", { status: b.dataset.s, remarks });
      toast("Claim " + b.dataset.s.toLowerCase());
      await load();
    } catch (err) { toast(err.message, true); }
  });
  bind("f", async (d, f) => { await api("/claims", "POST", d); toast("Claim submitted"); f.reset(); $("#pv").textContent = ""; await load(); });
  await load();
};

// ---------------- Start ----------------
document.querySelectorAll("nav button").forEach((b) => b.addEventListener("click", () => show(b.dataset.view)));
show("dashboard");
