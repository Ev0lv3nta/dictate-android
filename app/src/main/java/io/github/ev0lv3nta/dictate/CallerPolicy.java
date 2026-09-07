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
        if (attributedPackage != null) {
            if (!allowedPackages.contains(attributedPackage) || uidPackages == null) return false;
            for (String name : uidPackages) if (attributedPackage.equals(name)) return true;
            return false;
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
