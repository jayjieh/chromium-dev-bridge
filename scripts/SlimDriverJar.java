import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Makes a platform-specific driver jar by removing the other platforms' Playwright Node.js runtimes from the nested
 * driver-bundle jar (~229 MB for all five platforms). Spring Boot requires nested jars to be STORED, so they are
 * written uncompressed with their CRC.
 *
 * Usage: java scripts/SlimDriverJar.java <in.jar> <out.jar> <platform>
 * Platforms: win32_x64, mac, mac-arm64, linux, linux-arm64
 */
public class SlimDriverJar {
    static final Set<String> PLATFORMS = Set.of("win32_x64", "mac", "mac-arm64", "linux", "linux-arm64");

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !PLATFORMS.contains(args[2])) {
            System.err.println("Usage: java SlimDriverJar.java <in.jar> <out.jar> <" + String.join("|", PLATFORMS) + ">");
            System.exit(2);
        }
        String keep = "driver/" + args[2] + "/";
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (e.getName().startsWith("BOOT-INF/lib/driver-bundle-") && e.getName().endsWith(".jar")) {
                    data = filterBundle(data, keep);
                }
                ZipEntry n = new ZipEntry(e.getName());
                n.setTime(e.getTime());
                if (e.getMethod() == ZipEntry.STORED || e.getName().startsWith("BOOT-INF/lib/")) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    n.setMethod(ZipEntry.STORED);
                    n.setSize(data.length);
                    n.setCompressedSize(data.length);
                    n.setCrc(crc.getValue());
                }
                out.putNextEntry(n);
                out.write(data);
                out.closeEntry();
            }
        }
    }

    static byte[] filterBundle(byte[] jar, String keep) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar));
             ZipOutputStream out = new ZipOutputStream(buf)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                boolean otherPlatform = name.startsWith("driver/") && !name.equals("driver/") && !name.startsWith(keep);
                if (otherPlatform) continue;
                ZipEntry n = new ZipEntry(name);
                n.setTime(e.getTime());
                out.putNextEntry(n);
                out.write(in.readAllBytes());
                out.closeEntry();
            }
        }
        return buf.toByteArray();
    }
}
