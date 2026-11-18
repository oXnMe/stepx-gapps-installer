package com.stepx.gapps.installer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把下载好的新包递给系统安装器的出口。
 *
 * 只能走 content://：`file://` 在 targetSdk 24 起会抛 FileUriExposedException，
 * Android 16 的安装器本身也拒收 file://（报「无法获取软件包信息」）。自己开一个
 * 只读的 provider，比引一整个 androidx FileProvider 省事 —— 这个项目没有 AndroidX。
 *
 * 暴露面刻意压到最小：只服务 cache/update 这一层目录下的单层文件名，
 * 别的路径一律当不存在；写入一律拒绝。
 */
public class ApkProvider extends ContentProvider {

    static final String AUTHORITY = "com.stepx.gapps.installer.apk";

    /** 下载目录名，和 {@link Update#download} 用的是同一个。 */
    private static final String DIR = "update";
    private static final String MIME = "application/vnd.android.package-archive";

    /** 给 cache/update 下的某个文件拼一个外面能读的 content:// 地址。 */
    static Uri uriFor(File apk) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(apk.getName()).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.contains("..")) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(new File(getContext().getCacheDir(), DIR), name);
        if (!f.isFile()) throw new FileNotFoundException(String.valueOf(uri));
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("只读：" + mode);
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * 安装器会先查一遍尺寸和显示名（读不到 SIZE 时有的实现会拒绝安装），
     * 所以这里把 {@link OpenableColumns} 两列补齐。
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] args, String order) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null && projection.length > 0 ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return MIME;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
