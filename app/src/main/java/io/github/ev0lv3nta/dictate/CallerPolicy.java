package io.github.ev0lv3nta.dictate;

import java.util.Set;

final class CallerPolicy {

    private CallerPolicy() {
    }

    static boolean isAllowed(String attributedPackage, String[] uidPackages,
                             Set<String> allowedPackages) {
        if (allowedPackages == null || allowedPackages.isEmpty()) {
            return false;
        }
        if (attributedPackage != null && allowedPackages.contains(attributedPackage)) {
            return true;
        }
        if (uidPackages != null) {
            for (String packageName : uidPackages) {
                if (allowedPackages.contains(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
