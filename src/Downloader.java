import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Scanner;

public class Downloader {

    private static String cveJson = "/mnt/Drive-1/Development/Other/Android_ROMs/Patches/Linux/Kernel_CVE_Patch_List.txt";
    private static String output = "/mnt/Drive-1/Development/Other/Android_ROMs/Patches/Linux/";
    private static ArrayList<CVE> cves = new ArrayList<CVE>();
    private static final String userAgent = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/42.0.2311.90 Safari/537.36";

    public static void main(String[] args) {
        //Read in all the CVEs from the JSON file
        try {
            System.out.println("Parsing...");
            Scanner cve = new Scanner(new File(cveJson));
            String name = "";
            boolean depends = false;
            ArrayList<Link> links = new ArrayList<Link>();
            while (cve.hasNextLine()) {
                String line = cve.nextLine();
                if (line.startsWith("#")) {
                    //Comment, ignore
                } else if (line.startsWith("CVE") || line.startsWith("LVT") || line.startsWith("00") || !cve.hasNextLine()) {
                    if (name.length() > 0) {
                        cves.add(new CVE(name, depends, links));
                        System.out.println("\t\tAdded " + links.size() + " links");
                        links = new ArrayList<Link>();
                        name = "";
                        depends = false;
                    }
                    if (cve.hasNextLine()) {
                        name = line;
                        System.out.println("\t" + name);
                    }
                } else if (line.contains("Depends")) {
                    depends = true;
                } else if (line.contains("Link - ")) {
                    String[] lineS = line.split(" - ");
                    String link = "";
                    String version = "";
                    if (lineS.length > 2) {
                        version = lineS[1];
                        link = lineS[2];
                    } else {
                        version = "ANY";
                        link = lineS[1];
                    }
                    links.add(new Link(link, version));
                    System.out.println("\t\tAdded a new link to " + link);
                }
            }
            cve.close();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("Downloading patches...");
        boolean skipIfExists = true;
        for (CVE cve : cves) {
            if (!(skipIfExists && new File(output + cve.getId()).exists())) {
                System.out.println("\t" + cve.getId());
                //Only run if we have patches available
                if (cve.getLinks().size() > 0) {
                    if (cve.getDepends()) {
                        File depends = new File(output + cve.getId() + "/depends");
                        depends.mkdirs();
                        File dependsAdd = new File(output + cve.getId() + "/depends/.NotEmpty");
                        try {
                            dependsAdd.createNewFile();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    //Iterate over all links and download if needed
                    int linkC = 1;
                    for (Link link : cve.getLinks()) {
                        String patch = getPatchURL(link);
                        if (!patch.equals("NOT A PATCH")) {
                            File outDir = new File(output + cve.getId() + "/" + link.getVersion());
                            outDir.mkdirs();
                            String base64 = "";
                            if (isBase64Encoded(link)) {
                                base64 = ".base64";
                            }
                            String patchOutput = outDir.getAbsolutePath() + "/" + String.format("%04d", linkC) + ".patch" + base64;
                            downloadFile(patch, new File(patchOutput), false);
                            if (isBase64Encoded(link)) {
                                try {
                                    Process b64dec = Runtime.getRuntime().exec(new String[] {"/bin/sh", "-c", "base64 -d " + patchOutput + " > " + patchOutput.replaceAll(base64, "")});
                                    while (b64dec.isAlive()) {
                                        //Do nothing
                                    }
                                    if (b64dec.exitValue() != 0) {
                                        System.out.println("Failed to decode patch - " + patch);
                                        System.exit(1);
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                            System.out.println("\t\tDownloaded " + link.getURL());
                            linkC++;
                        } else {
                            System.out.println("NOT A PATCH - " + link);
                        }
                    }
                }
            }
        }
        System.out.println("Success!");
    }

    private static String getPatchURL(Link link) {
        String url = link.getURL().replaceAll("http://", "https://");
        if (url.contains("github.com")) {
            return url + ".patch";
        } else if (url.contains("git.kernel.org")) {
            return url.replaceAll("cgit/", "pub/scm/").replaceAll("commit", "patch");
        } else if (url.contains("codeaurora.org")) {
            return url.replaceAll("commit", "patch");
        } else if (url.contains("android.googlesource.com")) {
            String add = "";
            if (!url.contains("%5E%21")) {
                add += "%5E%21/";
            }
            add += "?format=TEXT";
            return url.replaceAll("/#F0", "") + add; //BASE64 ENCODED
        } else if (url.contains("review.lineageos.org") && !url.contains("topic") && !url.contains("#/q")) {
            int idS = 3;
            if (url.contains("#/c")) {
                idS = 5;
            }
            String id = url.split("/")[idS];
            //TODO: Dynamically get revision
            return "https://review.lineageos.org/changes/" + id + "/revisions/1/patch?download"; //BASE64 ENCODED
        } else if (url.contains("android-review.googlesource.com") && !url.contains("topic") && !url.contains("#/q")) {
            int idS = 3;
            if (url.contains("#/c")) {
                idS = 5;
            }
            String id = url.split("/")[idS];
            //TODO: Dynamically get revision
            return "https://android-review.googlesource.com/changes/" + id + "/revisions/1/patch?download"; //BASE64 ENCODED
        } else if (url.contains("patchwork")) {
            return (url + "/raw").replaceAll("//raw", "/raw");
        }
        return "NOT A PATCH";
    }

    private static boolean isBase64Encoded(Link link) {
        if (link.getURL().contains("android.googlesource.com") || link.getURL().contains("review.lineageos.org") || link.getURL().contains("android-review.googlesource.com")) {
            return true;
        }
        return false;
    }

    public static void downloadFile(String url, File out, boolean useCache) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(45000);
            connection.setReadTimeout(45000);
            connection.addRequestProperty("User-Agent", userAgent);
            if (useCache && out.exists()) {
                connection.setIfModifiedSince(out.lastModified());
            }
            connection.connect();
            int res = connection.getResponseCode();
            if (res != 304 && (res == 200 || res == 301 || res == 302)) {
                Files.copy(connection.getInputStream(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            connection.disconnect();
        } catch (Exception e) {
            //System.out.println("Throttling? Too many files open?");
            e.printStackTrace();
            try {
                Thread.sleep(30000L);
                downloadFile(url, out, useCache);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
            //System.exit(1);
        }
    }

    public static class CVE {
        private String id;
        private boolean depends;
        private ArrayList<Link> links;

        public CVE(String id, boolean depends, ArrayList<Link> links) {
            this.id = id;
            this.depends = depends;
            this.links = links;
        }

        public String getId() {
            return id;
        }

        public boolean getDepends() {
            return depends;
        }

        public ArrayList<Link> getLinks() {
            return links;
        }
    }


    public static class Link {
        private String url;
        private String version;

        public Link(String url, String version) {
            this.url = url;
            this.version = version;
        }

        public String getURL() {
            return url;
        }

        public String getVersion() {
            return version;
        }
    }

}
