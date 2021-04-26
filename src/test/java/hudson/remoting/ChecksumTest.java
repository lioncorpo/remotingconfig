package hudson.remoting;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import org.jenkinsci.remoting.util.Charsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/**
 * Tests for {@link Checksum}.
 *
 * @author Akshay Dayal
 */
public class ChecksumTest {

    private static final String FILE_CONTENTS1 = "These are the file contents";
    private static final String FILE_CONTENTS2 = "These are some other file contents";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testForFileAndURL() throws Exception {
        File tmpFile1 = createTmpFile("file1.txt", FILE_CONTENTS1);
        File tmpFile2 = createTmpFile("file2.txt", FILE_CONTENTS2);
        HashCode hash1 = Files.hash(tmpFile1, Hashing.sha256());
        HashCode hash2 = Files.hash(tmpFile2, Hashing.sha256());

        assertEquals(createdExpectedChecksum(hash1), Checksum.forFile(tmpFile1));
        assertEquals(createdExpectedChecksum(hash1), Checksum.forURL(tmpFile1.toURI().toURL()));

        assertEquals(createdExpectedChecksum(hash2), Checksum.forFile(tmpFile2));
        assertEquals(createdExpectedChecksum(hash2), Checksum.forURL(tmpFile2.toURI().toURL()));

        assertNotEquals(Checksum.forFile(tmpFile1), Checksum.forFile(tmpFile2));
    }

    @Test
    public void testCaching() throws Exception {
        File tmpFile = createTmpFile("file.txt", FILE_CONTENTS1);
        HashCode hash = Files.hash(tmpFile, Hashing.sha256());
        assertEquals(createdExpectedChecksum(hash), Checksum.forFile(tmpFile));

        tmpFile.delete();
        assertFalse(tmpFile.exists());
        assertEquals(createdExpectedChecksum(hash), Checksum.forFile(tmpFile));
    }

    private File createTmpFile(String name, String contents) throws Exception {
        File tmpFile = tmp.newFile(name);
        Files.append(contents, tmpFile, Charsets.UTF_8);
        return tmpFile;
    }

    static Checksum createdExpectedChecksum(HashCode hashCode) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(hashCode.asBytes()));
        long sum1 = 0;
        long sum2 = 0;
        for (int i = 0; i < hashCode.asBytes().length / 8; i++) {
            long nextLong = in.readLong();
            if (i % 2 == 0) {
                sum1 ^= nextLong;
            }
            else {
                sum2 ^= nextLong;
            }
        }
        return new Checksum(sum1, sum2);
    }
}
