package io.github.adiker.iconpacktomtz.integration.shizuku;

interface IInstalledAppsService {
    List<String> listPackages();
    void destroy();
}
