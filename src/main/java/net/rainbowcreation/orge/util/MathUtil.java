package net.rainbowcreation.orge.util;

public class MathUtil {
    public static double getDistance(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    public static double getDistance(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean isLesserThan(double x1, double z1, double x2, double z2, double squaredistance) {
        return getDistance(x1, z1, x2, z2) < squaredistance;
    }

    public static boolean isLesserThan(double x1, double z1, double x2, double z2, int distance) {
        return isLesserThan(x1, z1, x2, z2, (double) distance * distance);
    }

    public static boolean isLesserThan(double x1, double y1, double z1, double x2, double y2, double z2, double squaredistance) {
        return getDistance(x1, y1, z1, x2, y2, z2) < squaredistance;
    }

    public static boolean isLesserThan(double x1, double y1, double z1, double x2, double y2, double z2, int distance) {
        return isLesserThan(x1, y1, z1, x2, y2, z2, (double) distance * distance);
    }

    public static boolean isFartherThan(double x1, double z1, double x2, double z2, double squaredistance) {
        return getDistance(x1, z1, x2, z2) > squaredistance;
    }

    public static boolean isFartherThan(double x1, double z1, double x2, double z2, int distance) {
        return isFartherThan(x1, z1, x2, z2, (double) distance * distance);
    }

    public static boolean isFartherThan(double x1, double y1, double z1, double x2, double y2, double z2, double squaredistance) {
        return getDistance(x1, y1, z1, x2, y2, z2) > squaredistance;
    }

    public static boolean isFartherThan(double x1, double y1, double z1, double x2, double y2, double z2, int distance) {
        return isFartherThan(x1, y1, z1, x2, y2, z2, (double) distance * distance);
    }
}
