package global.playground.warden;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

class SignAPK extends Object {
  public static void main(String[] args) {
    if (args.length < 4) {
      printUsage();
      System.exit(-1);
    }

    // skip the arguments that the build might pass to standard signapk, since we don't need them:
    // we just send our input files to Warden, which is responsible for such configuration
    int i = 0;
    while (i < args.length) {
      String arg = args[i];

      if ("-w".equals(arg)) {
        i++;
        continue;
      }

      if ("--min-sdk-version".equals(arg)) {
        i += 2;
        continue;
      }

      if (!arg.startsWith("-")) {
        break;
      } else {
        i++;
      }
    }

    // the args are [cert0 key0 cert1 key1 ... certN keyN input output]
    if ((args.length - i) % 2 != 0) {
      printUsage();
      System.exit(-1);
    }

    String out = args[args.length - 1];
    String in = args[args.length - 2];
    byte[] outBytes, inBytes = null;

    try {
      inBytes = Files.readAllBytes(FileSystems.getDefault().getPath(in));
    } catch(IOException e) {
      die("error loading input file: " + e.toString());
    }

    String urlBase = "https://" + Params.server + ":" + Params.port + "/sign/" + Params.product + "-" + Params.keyset + "-";
    while (i < (args.length - 2)) {
      try {
        String cert = extractCertName(args[i]);
        String url = urlBase + cert;

        outBytes = submitBinary(url, inBytes);
        inBytes = null;

        Files.write(FileSystems.getDefault().getPath(out), outBytes);

        i += 2; // keys reside on server, we only need 1 filename to get the info we need


        // TODO?: support multiple keys to sign, if necessary
        // standard signapk supports signing with multiple keys in v1 cases; it does not in v2 cases.
        // Warden supports multiple signing keys in both cases, but the build obviously does not,
        // since signapk does not for modern devices. So for now, we just use the first cert config we
        // see and bail.
        break;
      } catch(Exception e) {
        die("exception during execution for cert '" + args[i] + "' (" + e.toString() + ")");
      }
    }
  }

  protected static void printUsage() {
    System.out.println("usage: SignAPK [flags] <cert> <key> [<cert> <key> ...] <input> <output>\n");
    System.out.println("This program is command-line compatible with the AOSP signapk.jar, but");
    System.out.println("rather than sign files locally, it defers to a Warden signing server instance.\n");
    System.out.println("Configuration is via environment variables.");
    System.out.println("Multiple cert/key pairs after the first pair are ignored.");
    System.out.println("Command-line flags (starting with '-') prior to input files are ignored.");
  }

  protected static String extractCertName(String path) throws IllegalArgumentException {
    File f = new File(path);
    String base = f.getName();

    if ("".equals(base)) {
      throw new IllegalArgumentException("unable to parse filename");
    }

    int i = base.indexOf(".");
    if (i == 0) {
      throw new IllegalArgumentException("unable to parse filename");
    }

    if (i < 0) {
      return base;
    }

    return base.substring(0, i);
  }

  protected static byte[] submitBinary(String url, byte[] bytes) throws IOException {
    final HostnameVerifier hv = new HostnameVerifier() {
      public boolean verify(String hostname, SSLSession session) { return true; }
    };

    final TrustManager[] tm = new TrustManager[] { 
      new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException { }

        public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
          if (authType == null || certs == null || certs.length == 0) {
            throw new IllegalArgumentException();
          }
          boolean legit = false;
          for (X509Certificate cert : certs) {
            if (cert.equals(Params.serverCert)) {
              legit = true;
              break;
            }
          }
          if (!legit) {
            throw new CertificateException();
          }
        }
    }};

    final KeyManager[] km = new KeyManager[] {
      new X509KeyManager() {
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return "whatever"; }
        public String[] getServerAliases(String keyType, Principal[] issuers) { return new String[]{ "whatever" }; }

        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return "whatever"; }
        public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[]{ "whatever" }; }

        public X509Certificate[] getCertificateChain(String alias) {
          return new X509Certificate[]{ Params.clientCert };
        }
        public PrivateKey getPrivateKey(String alias) {
          return Params.clientKey;
        }
    }};

    byte[] ret;
    try {
      // set up our server cert-pinning validation scheme
      SSLContext ctx = SSLContext.getInstance("TLSv1.2");
      ctx.init(km, tm, new SecureRandom());
      HttpsURLConnection cxn = (HttpsURLConnection)(new URL(url).openConnection());
      cxn.setHostnameVerifier(hv);
      cxn.setSSLSocketFactory(ctx.getSocketFactory());

      // set up the actual query headers
      cxn.setRequestProperty("Content-Type", "application/octet-stream");
      cxn.setRequestMethod("POST");
      cxn.setDoInput(true);
      cxn.setDoOutput(true);

      // connect and upload our data
      cxn.connect();
      BufferedOutputStream bos = new BufferedOutputStream(cxn.getOutputStream());
      bos.write(bytes);
      bos.close();
      bytes = null;

      // see what the server has to say for itself
      int rc = cxn.getResponseCode();
      if (rc > 299) {
        throw new IOException("server returned non-okay message");
      }

      // read the server's response data into a byte[]
      BufferedInputStream bis = new BufferedInputStream(cxn.getInputStream());
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      int n;
      byte[] buf = new byte[1024];
      while ((n = bis.read(buf, 0, 1024)) > 0) {
        baos.write(buf, 0, n);
      }
      bis.close();
      ret = baos.toByteArray();
    } catch(Exception e) {
      throw new IOException(e);
    }

    return ret;
  }

  protected static void die(String message) {
    System.err.println("FATAL ERROR -- aborting");
    System.err.println(message);
    System.exit(-2);
  }

  protected static class Params extends Object {
    public static String server;
    public static int port;
    public static String product;
    public static String keyset;
    public static String clientCertPath;
    public static X509Certificate clientCert;
    public static String clientKeyPath;
    public static PrivateKey clientKey;
    public static String serverCertPath;
    public static X509Certificate serverCert;

    static {
      String k, v;
      Map<String, String> all = System.getenv();

      if (!all.containsKey("WARDEN_PRODUCT")) die("missing WARDEN_PRODUCT");
      if (!all.containsKey("WARDEN_SERVER_CERT")) die("missing WARDEN_SERVER_CERT");

      product = all.get("WARDEN_PRODUCT");

      serverCertPath = all.get("WARDEN_SERVER_CERT");
      try {
        FileInputStream fis = new FileInputStream(new File(serverCertPath));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        serverCert = (X509Certificate)cf.generateCertificate(fis);
        fis.close();
      } catch(Exception e) {
        serverCertPath = "";
        serverCert = null;
      }

      if (all.containsKey("WARDEN_HOST")) {
        server = all.get("WARDEN_HOST");
      } else {
        server = "localhost";
      }

      if (all.containsKey("WARDEN_PORT")) {
        try {
          port = Integer.parseInt(all.get("WARDEN_PORT"));
        } catch(NumberFormatException e) {
          die("WARDEN_PORT must be an integer");
        }
      } else {
        port = 9000;
      }

      if (all.containsKey("WARDEN_KEYSET")) {
        keyset = all.get("WARDEN_KEYSET");
      } else {
        keyset = "dev";
      }

      if (all.containsKey("WARDEN_CLIENT_CERT")) {
        clientCertPath = all.get("WARDEN_CLIENT_CERT");
      } else {
        clientCertPath = "./client.crt";
      }
      try {
        FileInputStream fis = new FileInputStream(new File(clientCertPath));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        clientCert = (X509Certificate)cf.generateCertificate(fis);
        fis.close();
      } catch(Exception e) {
        System.err.println("error parsing client certificate: " + e.toString());
        clientCertPath = "";
        clientCert = null;
      }

      if (all.containsKey("WARDEN_CLIENT_KEY")) {
        clientKeyPath = all.get("WARDEN_CLIENT_KEY");
      } else {
        clientKeyPath = "./client.key";
      }
      try {
        File f = new File(clientCertPath);
        List<String> lines = Files.readAllLines(FileSystems.getDefault().getPath(clientKeyPath));
        lines = lines.subList(1, lines.size() - 1);
        StringBuffer sb = new StringBuffer();
        for (String line : lines) {
          sb.append(line.trim());
        }
        clientKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(sb.toString())));
      } catch(Exception e) {
        System.err.println("error parsing client key: " + e.toString());
        clientKeyPath = "";
        clientKey = null;
      }
    }
  }
}
