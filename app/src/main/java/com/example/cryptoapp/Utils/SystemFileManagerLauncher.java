package com.example.cryptoapp.Utils;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 只启动处理 SAF 的系统 DocumentsUI，不再打开厂商普通文件管理器的首页。
 *
 * 不同 ROM 可以替换 DocumentsUI 的包名，因此以 ACTION_OPEN_DOCUMENT_TREE 的真实解析结果为准，
 * 再结合组件名和系统应用标记排序；setComponent() 确保打开的是文档选择 Activity。
 */
public final class SystemFileManagerLauncher {
    private SystemFileManagerLauncher() { }

    public static String targetDocumentId() { return "primary:Android/data"; }

    public static void open(Activity activity) {
        Intent tree = documentsTreeIntent(Uri.parse(
                "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata"));
        for (ComponentName component : resolveDocumentsUiComponents(activity, tree)) {
            if (tryStart(activity, new Intent(tree).setComponent(component))) return;
        }

        // ROM 可能不公开可查询组件，但仍能通过系统默认解析器进入 DocumentsUI。
        if (tryStart(activity, tree)) return;

        Intent document = openDocumentIntent();
        for (ComponentName component : resolveDocumentsUiComponents(activity, document)) {
            if (tryStart(activity, new Intent(document).setComponent(component))) return;
        }
        if (tryStart(activity, document)) return;

        Toast.makeText(activity, "系统未提供可用的 DocumentsUI，已打开存储设置", Toast.LENGTH_LONG).show();
        tryStart(activity, new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
    }

    public static Intent documentsTreeIntent(Uri initialUri) {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                .putExtra("android.content.extra.SHOW_ADVANCED", true)
                .putExtra("android.content.extra.FANCY", true)
                .putExtra("android.content.extra.SHOW_FILESIZE", true);
    }

    private static Intent openDocumentIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(
                        "content://com.android.externalstorage.documents/root/primary"))
                .putExtra("android.content.extra.SHOW_ADVANCED", true)
                .putExtra("android.content.extra.FANCY", true)
                .putExtra("android.content.extra.SHOW_FILESIZE", true);
    }

    /** 返回实际响应 SAF Intent 的 Activity；DocumentsUI 命名组件优先，ROM 自定义组件随后。 */
    static List<ComponentName> resolveDocumentsUiComponents(Activity activity, Intent intent) {
        List<ResolveInfo> resolved = activity.getPackageManager().queryIntentActivities(intent, 0);
        resolved.sort(Comparator.comparingInt(SystemFileManagerLauncher::componentPriority));
        LinkedHashSet<ComponentName> components = new LinkedHashSet<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            components.add(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
        }
        return new ArrayList<>(components);
    }

    static int componentPriority(ResolveInfo info) {
        if (info.activityInfo == null) return 3;
        String identity = (info.activityInfo.packageName + " " + info.activityInfo.name).toLowerCase(Locale.ROOT);
        if (identity.contains("documentsui") || identity.contains("documentui")) return 0;
        if ((info.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return 1;
        return 2;
    }

    private static boolean tryStart(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }
}
