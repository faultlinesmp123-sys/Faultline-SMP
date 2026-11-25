package net.faultlinesmp.items;

/** 1.3.57: for FaultlineDeep (by reflection): is a spot in the Dark Ocean inside an abyssal Spider Nest (a dry pocket)? */
public final class NestApi {
    private NestApi() { }

    public static boolean inAbyssNest(double x, double y, double z) {
        SpiderNest s = SpiderNest.INSTANCE;
        return s != null && s.inAbyssNest(x, y, z);
    }
}
