// Masa de Poker pentru Windows: jocul și Jarvis rulează local, pe calculatorul tău.
// Programul pornește un mic server doar pe 127.0.0.1 (nu e accesibil din rețea) care:
//   - servește jocul (/) și pe Jarvis (/jarvis), incluse în .exe;
//   - leagă jocul de Jarvis printr-un WebSocket (/ws);
//   - salvează datele lui Jarvis în %LOCALAPPDATA%\MasaDePoker\date (/data/...).
// Se compilează de două ori: Masa de Poker.exe (lansatorul) și Jarvis.exe (cu /define:JARVIS).
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

#if JARVIS
[assembly: AssemblyTitle("Jarvis")]
[assembly: AssemblyProduct("Jarvis pentru Masa de Poker")]
#else
[assembly: AssemblyTitle("Masa de Poker")]
[assembly: AssemblyProduct("Masa de Poker")]
#endif
[assembly: AssemblyVersion("2.0.0.0")]

static class App
{
    public const string OnlineUrl = "https://poker-server-production-cb58.up.railway.app/";
    public static readonly string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MasaDePoker");
    public static int Port = 47123;
    public static LocalServer Server;

    public static string Base { get { return "http://127.0.0.1:" + Port; } }

    [STAThread]
    static void Main()
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Directory.CreateDirectory(DataDir);
        bool hosting;
        try { hosting = EnsureServer(); }
        catch (Exception ex)
        {
            MessageBox.Show("Serverul local nu a putut porni: " + ex.Message, "Masa de Poker", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
#if JARVIS
        OpenJarvis();
        if (!hosting) return; // un alt program Masa de Poker ține deja serverul pornit
        Application.Run(new Tray(null));
#else
        Application.Run(new Tray(new Launcher()));
#endif
    }

    // Folosește serverul deja pornit de celălalt program sau pornește unul nou.
    static bool EnsureServer()
    {
        for (int p = 47123; p < 47133; p++)
        {
            if (Ping(p)) { Port = p; return false; }
            LocalServer s = new LocalServer();
            if (s.Start(p)) { Port = p; Server = s; return true; }
        }
        throw new Exception("niciun port liber între 47123 și 47132");
    }

    static bool Ping(int port)
    {
        try
        {
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:" + port + "/ping");
            r.Timeout = 700; r.Proxy = null;
            using (WebResponse resp = r.GetResponse())
            using (StreamReader sr = new StreamReader(resp.GetResponseStream()))
                return sr.ReadToEnd() == "masa-de-poker";
        }
        catch { return false; }
    }

    static string FindEdge()
    {
        string[] roots = {
            Environment.GetEnvironmentVariable("ProgramFiles(x86)"),
            Environment.GetEnvironmentVariable("ProgramFiles"),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData)
        };
        foreach (string r in roots)
        {
            if (string.IsNullOrEmpty(r)) continue;
            string p = Path.Combine(r, @"Microsoft\Edge\Application\msedge.exe");
            if (File.Exists(p)) return p;
        }
        return null;
    }

    public static void OpenApp(string url, int w, int h)
    {
        string edge = FindEdge();
        if (edge == null) { Process.Start(url); return; }
        string profile = Path.Combine(DataDir, "Edge");
        Directory.CreateDirectory(profile);
        string args = "--app=\"" + url + "\" --user-data-dir=\"" + profile + "\" --no-first-run --window-size=" + w + "," + h;
        Process.Start(new ProcessStartInfo(edge, args) { UseShellExecute = false });
    }
    public static void OpenGame() { OpenApp(Base + "/", 1320, 900); }
    public static void OpenJarvis() { OpenApp(Base + "/jarvis", 520, 940); }
    public static void OpenOnline() { OpenApp(OnlineUrl, 1320, 900); }
    public static void OpenData()
    {
        string d = Path.Combine(DataDir, "date");
        Directory.CreateDirectory(d);
        Process.Start("explorer.exe", "\"" + d + "\"");
    }
}

// Iconița din bara de sistem: programul rămâne pornit cât timp jocul sau Jarvis au nevoie de serverul local.
class Tray : ApplicationContext
{
    readonly NotifyIcon icon;
    readonly Form form;
    bool told;

    public Tray(Form f)
    {
        form = f;
        icon = new NotifyIcon();
        try { icon.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { icon.Icon = SystemIcons.Application; }
        icon.Text = "Masa de Poker rulează local";
        ContextMenu menu = new ContextMenu();
        menu.MenuItems.Add("Antrenament cu boți", delegate { Safe(App.OpenGame); });
        menu.MenuItems.Add("Jarvis", delegate { Safe(App.OpenJarvis); });
        menu.MenuItems.Add("Joc online cu prietenii", delegate { Safe(App.OpenOnline); });
        menu.MenuItems.Add("Datele lui Jarvis", delegate { Safe(App.OpenData); });
        menu.MenuItems.Add("-");
        menu.MenuItems.Add("Ieșire", delegate { Quit(); });
        icon.ContextMenu = menu;
        icon.DoubleClick += delegate { if (form != null) { form.Show(); form.Activate(); } else Safe(App.OpenJarvis); };
        icon.Visible = true;
        if (form != null)
        {
            form.FormClosing += (s, e) =>
            {
                if (e.CloseReason != CloseReason.UserClosing) return;
                e.Cancel = true; form.Hide();
                if (!told) { told = true; icon.ShowBalloonTip(4000, "Masa de Poker", "Rulează în continuare aici, ca jocul și Jarvis să rămână legate. Ieși din meniul iconiței.", ToolTipIcon.Info); }
            };
            form.Show();
        }
    }

    static void Safe(Action a)
    {
        try { a(); } catch (Exception ex) { MessageBox.Show("Nu s-a putut deschide: " + ex.Message, "Masa de Poker", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
    }

    void Quit()
    {
        icon.Visible = false;
        if (App.Server != null) App.Server.Stop();
        if (form != null) form.Dispose();
        ExitThread();
    }
}

class Launcher : Form
{
    static readonly Color Ink = Color.FromArgb(12, 21, 18);
    static readonly Color Panel = Color.FromArgb(19, 31, 27);
    static readonly Color Brass = Color.FromArgb(220, 174, 85);
    static readonly Color Cream = Color.FromArgb(236, 230, 214);
    static readonly Color Muted = Color.FromArgb(152, 172, 161);

    public Launcher()
    {
        Text = "Masa de Poker";
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Ink; ForeColor = Cream;
        ClientSize = new Size(460, 420);
        Font = new Font("Segoe UI", 10f);

        Add(new Label { Text = "MASA DE POKER", Font = new Font("Segoe UI", 22f, FontStyle.Bold), ForeColor = Brass, AutoSize = true, Location = new Point(28, 20) });
        Add(new Label { Text = "No-Limit Hold'em · jetoane de joc, fără bani reali", ForeColor = Muted, AutoSize = true, Location = new Point(31, 66) });

        Button game = MakeButton("Antrenament cu boți", "Cash game sau turneu. Merge fără internet.", 100, true);
        game.Click += delegate { App.OpenGame(); };
        Add(game);
        Button jarvis = MakeButton("Jarvis", "Antrenorul separat. Se leagă singur de joc.", 184, false);
        jarvis.Click += delegate { App.OpenJarvis(); };
        Add(jarvis);
        Button online = MakeButton("Joc online cu prietenii", "Singura parte care folosește internetul.", 268, false);
        online.Click += delegate { App.OpenOnline(); };
        Add(online);

        Add(new Label
        {
            Text = "Jocul și Jarvis rulează pe calculatorul tău. Datele lui Jarvis se salvează în\n%LOCALAPPDATA%\\MasaDePoker\\date.",
            ForeColor = Muted, AutoSize = true, Location = new Point(28, 362)
        });
    }

    void Add(Control c) { Controls.Add(c); }

    Button MakeButton(string text, string hint, int top, bool primary)
    {
        Button b = new Button();
        b.Text = text + "\n" + hint;
        b.TextAlign = ContentAlignment.MiddleLeft;
        b.Padding = new Padding(14, 0, 0, 0);
        b.FlatStyle = FlatStyle.Flat;
        b.FlatAppearance.BorderColor = Brass;
        b.FlatAppearance.BorderSize = 1;
        b.BackColor = primary ? Brass : Panel;
        b.ForeColor = primary ? Ink : Cream;
        b.Font = new Font("Segoe UI", 10.5f, FontStyle.Bold);
        b.Cursor = Cursors.Hand;
        b.Location = new Point(28, top);
        b.Size = new Size(404, 72);
        return b;
    }
}

class WsClient
{
    public TcpClient Client;
    public NetworkStream Stream;
    public string Role;
    public readonly object Lock = new object();
}

// Server HTTP + WebSocket minimal, doar pe 127.0.0.1.
class LocalServer
{
    TcpListener listener;
    byte[] gameHtml, jarvisHtml;
    readonly List<WsClient> clients = new List<WsClient>();
    readonly object lk = new object();
    static readonly Regex DataName = new Regex("^[a-z0-9-]{1,40}$");

    public bool Start(int port)
    {
        try { listener = new TcpListener(IPAddress.Loopback, port); listener.Start(); }
        catch { return false; }
        gameHtml = Res("masa-de-poker.html");
        jarvisHtml = Res("jarvis.html");
        Thread t = new Thread(AcceptLoop); t.IsBackground = true; t.Start();
        return true;
    }

    public void Stop() { try { listener.Stop(); } catch { } }

    static byte[] Res(string name)
    {
        using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
        using (MemoryStream m = new MemoryStream()) { s.CopyTo(m); return m.ToArray(); }
    }

    void AcceptLoop()
    {
        while (true)
        {
            TcpClient c;
            try { c = listener.AcceptTcpClient(); } catch { return; }
            Thread t = new Thread(() => Handle(c)); t.IsBackground = true; t.Start();
        }
    }

    static string ReadHead(NetworkStream ns)
    {
        List<byte> buf = new List<byte>();
        while (buf.Count < 16384)
        {
            int b = ns.ReadByte(); if (b < 0) return null;
            buf.Add((byte)b);
            int n = buf.Count;
            if (n >= 4 && buf[n - 4] == 13 && buf[n - 3] == 10 && buf[n - 2] == 13 && buf[n - 1] == 10)
                return Encoding.ASCII.GetString(buf.ToArray(), 0, n - 4);
        }
        return null;
    }

    static byte[] ReadN(NetworkStream ns, int n)
    {
        byte[] b = new byte[n]; int got = 0;
        while (got < n) { int r = ns.Read(b, got, n - got); if (r <= 0) throw new IOException("închis"); got += r; }
        return b;
    }

    static bool LocalOrigin(string origin)
    {
        return origin == null || origin.StartsWith("http://127.0.0.1:") || origin.StartsWith("http://localhost:");
    }

    void Handle(TcpClient c)
    {
        try
        {
            c.NoDelay = true;
            NetworkStream ns = c.GetStream();
            string head = ReadHead(ns);
            if (head == null) { c.Close(); return; }
            string[] lines = head.Split(new[] { "\r\n" }, StringSplitOptions.None);
            string[] req = lines[0].Split(' ');
            string method = req[0], path = req.Length > 1 ? req[1] : "/", query = "";
            Dictionary<string, string> h = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            for (int i = 1; i < lines.Length; i++) { int k = lines[i].IndexOf(':'); if (k > 0) h[lines[i].Substring(0, k).Trim()] = lines[i].Substring(k + 1).Trim(); }
            int qi = path.IndexOf('?'); if (qi >= 0) { query = path.Substring(qi + 1); path = path.Substring(0, qi); }
            string origin; h.TryGetValue("Origin", out origin);

            if (path == "/ws" && h.ContainsKey("Sec-WebSocket-Key"))
            {
                if (!LocalOrigin(origin)) { Respond(ns, 403, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("Interzis")); c.Close(); return; }
                Session(c, ns, h["Sec-WebSocket-Key"], query.Contains("role=jarvis") ? "jarvis" : "game");
                return;
            }
            if (method == "GET" && path == "/ping") Respond(ns, 200, "text/plain", Encoding.UTF8.GetBytes("masa-de-poker"));
            else if (method == "GET" && path == "/") Respond(ns, 200, "text/html; charset=utf-8", gameHtml);
            else if (method == "GET" && path == "/jarvis") Respond(ns, 200, "text/html; charset=utf-8", jarvisHtml);
            else if (path.StartsWith("/data/")) Data(ns, method, path.Substring(6), h, origin);
            else Respond(ns, 404, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("Nu există"));
            c.Close();
        }
        catch { try { c.Close(); } catch { } }
    }

    // Datele lui Jarvis, ca fișiere JSON. Scrierea cere un antet propriu și o origine locală, ca alte site-uri să nu poată scrie aici.
    void Data(NetworkStream ns, string method, string name, Dictionary<string, string> h, string origin)
    {
        if (!DataName.IsMatch(name)) { Respond(ns, 400, "text/plain", Encoding.UTF8.GetBytes("nume greșit")); return; }
        string dir = Path.Combine(App.DataDir, "date");
        Directory.CreateDirectory(dir);
        string file = Path.Combine(dir, name + ".json");
        if (method == "GET")
        {
            if (!LocalOrigin(origin)) { Respond(ns, 403, "text/plain", Encoding.UTF8.GetBytes("Interzis")); return; }
            Respond(ns, 200, "application/json; charset=utf-8", File.Exists(file) ? File.ReadAllBytes(file) : Encoding.UTF8.GetBytes("{}"));
            return;
        }
        if (method == "POST")
        {
            string len, mark;
            if (!LocalOrigin(origin) || !h.TryGetValue("X-Masa", out mark) || !h.TryGetValue("Content-Length", out len)) { Respond(ns, 403, "text/plain", Encoding.UTF8.GetBytes("Interzis")); return; }
            int n = int.Parse(len);
            if (n < 0 || n > 20 * 1024 * 1024) { Respond(ns, 413, "text/plain", Encoding.UTF8.GetBytes("prea mare")); return; }
            byte[] body = ReadN(ns, n);
            string tmp = file + ".tmp";
            File.WriteAllBytes(tmp, body);
            if (File.Exists(file)) File.Replace(tmp, file, file + ".bak"); else File.Move(tmp, file);
            Respond(ns, 200, "application/json", Encoding.UTF8.GetBytes("{\"ok\":true}"));
            return;
        }
        Respond(ns, 405, "text/plain", Encoding.UTF8.GetBytes("metodă nepermisă"));
    }

    static void Respond(NetworkStream ns, int code, string type, byte[] body)
    {
        string reason = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Error";
        byte[] head = Encoding.ASCII.GetBytes("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.Length +
            "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n");
        ns.Write(head, 0, head.Length);
        ns.Write(body, 0, body.Length);
        ns.Flush();
    }

    void Session(TcpClient c, NetworkStream ns, string key, string role)
    {
        string accept;
        using (SHA1 sha = SHA1.Create())
            accept = Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
        byte[] resp = Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
        ns.Write(resp, 0, resp.Length);
        WsClient me = new WsClient { Client = c, Stream = ns, Role = role };
        lock (lk) clients.Add(me);
        Peers();
        try
        {
            MemoryStream msg = new MemoryStream();
            while (true)
            {
                byte[] hd = ReadN(ns, 2);
                bool fin = (hd[0] & 0x80) != 0;
                int op = hd[0] & 0x0F;
                bool masked = (hd[1] & 0x80) != 0;
                long len = hd[1] & 0x7F;
                if (len == 126) { byte[] e = ReadN(ns, 2); len = (e[0] << 8) | e[1]; }
                else if (len == 127) { byte[] e = ReadN(ns, 8); len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | e[i]; }
                if (len > 8 * 1024 * 1024) break;
                byte[] mask = masked ? ReadN(ns, 4) : null;
                byte[] data = ReadN(ns, (int)len);
                if (masked) for (int i = 0; i < data.Length; i++) data[i] ^= mask[i % 4];
                if (op == 8) break;
                if (op == 9) { Send(me, data, 0xA); continue; }
                if (op == 0xA) continue;
                msg.Write(data, 0, data.Length);
                if (fin)
                {
                    byte[] whole = msg.ToArray(); msg.SetLength(0);
                    string target = me.Role == "game" ? "jarvis" : "game";
                    foreach (WsClient w in Snapshot()) if (w.Role == target) Send(w, whole, 1);
                }
            }
        }
        catch { }
        lock (lk) clients.Remove(me);
        try { c.Close(); } catch { }
        Peers();
    }

    List<WsClient> Snapshot() { lock (lk) return new List<WsClient>(clients); }

    void Peers()
    {
        List<WsClient> all = Snapshot();
        int g = 0, j = 0;
        foreach (WsClient w in all) { if (w.Role == "game") g++; else j++; }
        byte[] m = Encoding.UTF8.GetBytes("{\"t\":\"peers\",\"game\":" + g + ",\"jarvis\":" + j + "}");
        foreach (WsClient w in all) Send(w, m, 1);
    }

    static void Send(WsClient w, byte[] payload, int op)
    {
        try
        {
            lock (w.Lock)
            {
                MemoryStream f = new MemoryStream();
                f.WriteByte((byte)(0x80 | op));
                if (payload.Length < 126) f.WriteByte((byte)payload.Length);
                else if (payload.Length < 65536) { f.WriteByte(126); f.WriteByte((byte)(payload.Length >> 8)); f.WriteByte((byte)payload.Length); }
                else { f.WriteByte(127); for (int i = 7; i >= 0; i--) f.WriteByte((byte)((long)payload.Length >> (8 * i))); }
                f.Write(payload, 0, payload.Length);
                byte[] b = f.ToArray();
                w.Stream.Write(b, 0, b.Length);
            }
        }
        catch { }
    }
}
