package com.example.webdavmanager;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// A separate test APK process has no access to the target APK's Kotlin runtime.
// This Java fixture exposes only test APK cache files and uses actual SAF grants.
public class LocalTestDocumentsProvider extends DocumentsProvider {
    private File base() {
        try { return new File(getContext().getCacheDir(), "documents").getCanonicalFile(); }
        catch (IOException e) { throw new IllegalArgumentException(e); }
    }
    @Override public boolean onCreate() { new File(base(), "fixtures").mkdirs(); return true; }
    private File file(String id) {
        try {
            File result = new File(base(), id).getCanonicalFile();
            if (!result.getPath().startsWith(base().getCanonicalPath() + File.separator)) throw new IllegalArgumentException("Invalid fixture ID");
            return result;
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }
    private String id(File file) { return file.getPath().substring(base().getPath().length() + 1); }
    private String mime(File file) {
        if (file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        String name = file.getName();
        String value = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(name.lastIndexOf('.') + 1));
        return value == null ? "application/octet-stream" : value;
    }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED } : projection);
    }
    private void add(MatrixCursor cursor, File file) {
        Map<String, Object> values = new HashMap<>();
        values.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id(file));
        values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.getName());
        values.put(DocumentsContract.Document.COLUMN_MIME_TYPE, mime(file));
        values.put(DocumentsContract.Document.COLUMN_SIZE, file.length());
        values.put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
        values.put(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_WRITE |
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME |
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE);
        Object[] row = new Object[cursor.getColumnCount()];
        for (int i = 0; i < row.length; i++) row[i] = values.get(cursor.getColumnNames()[i]);
        cursor.addRow(row);
    }
    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection == null ? new String[] { DocumentsContract.Root.COLUMN_ROOT_ID } : projection);
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) {
        if (file(documentId).getName().equals("slow-subtitle.ass") && projection != null && projection.length == 1) {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        MatrixCursor result = cursor(projection); add(result, file(documentId)); return result;
    }
    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        if (parentDocumentId.contains("fallback-slow-scan-")) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        MatrixCursor result = cursor(projection);
        File[] children = file(parentDocumentId).listFiles();
        if (children != null) for (File child : children) add(result, child);
        return result;
    }
    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return file(documentId).getPath().startsWith(file(parentDocumentId).getPath() + File.separator);
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        // Only the optional client API is unsupported. Framework tree-grant
        // enforcement still needs the real membership check for child queries.
        if ("android:isChildDocument".equals(method) && extras != null &&
                String.valueOf(extras.getParcelable("uri")).contains("fallback-")) {
            throw new UnsupportedOperationException("Test provider uses traversal");
        }
        return super.call(method, arg, extras);
    }
    @Override public String getDocumentType(String documentId) { return mime(file(documentId)); }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (mode.equals("r") && file(documentId).getName().equals("slow.bin")) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> {
                    try (java.io.InputStream input = new java.io.FileInputStream(file(documentId));
                         java.io.OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) >= 0) { output.write(buffer, 0, count); Thread.sleep(20); }
                    } catch (IOException | InterruptedException ignored) { }
                }).start();
                return pipe[0];
            } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        }
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode));
    }
    private void validateName(String name) {
        if (name.trim().isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
            throw new IllegalArgumentException("Invalid fixture name");
    }
    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        validateName(displayName);
        File next = new File(file(parentDocumentId), displayName);
        try {
            boolean created = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType) ? next.mkdir() : next.createNewFile();
            if (!created) throw new FileNotFoundException("Fixture create failed");
            return id(next);
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        validateName(displayName);
        File old = file(documentId);
        File next = new File(old.getParentFile(), displayName);
        if (!old.renameTo(next)) throw new FileNotFoundException("Fixture rename failed");
        return id(next);
    }
    private void remove(File file) throws FileNotFoundException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!file.delete()) throw new FileNotFoundException("Fixture delete failed");
    }
    @Override public void deleteDocument(String documentId) throws FileNotFoundException { remove(file(documentId)); }

    public static class GrantActivity extends Activity {
        @Override public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            String rootId = getIntent().getStringExtra("root");
            if (rootId == null || !rootId.startsWith("fixtures/") || rootId.contains("..")) throw new IllegalArgumentException("Invalid fixture root");
            new File(getCacheDir(), "documents/" + rootId).mkdirs();
            Uri tree = DocumentsContract.buildTreeDocumentUri("com.example.webdavmanager.test.documents", rootId);
            grantUriPermission("com.example.webdavmanager", tree, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            finish();
        }
    }
}
