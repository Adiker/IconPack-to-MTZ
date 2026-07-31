package io.github.adiker.iconpacktomtz.integration.shizuku;

interface IInstalledAppsService {
    // Reserved by Shizuku for terminating a non-daemon user service during unbind.
    void destroy() = 16777114;
    List<String> listPackages() = 1;
}
