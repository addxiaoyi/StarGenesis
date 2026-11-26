package dev.stargenesis.paper.lab;

/** Explicit command mode lets a Paper player observe the world without an open inventory. */
final class LabDisplayMode {
    private LabDisplayMode() {}
    static boolean commands(String[] args, int index, boolean folia) {
        if (args.length > index + 1) throw new IllegalArgumentException("Too many lab arguments");
        if (args.length <= index) return folia;
        if (args[index].equalsIgnoreCase("commands")) return true;
        if (args[index].equalsIgnoreCase("menu") && !folia) return false;
        throw new IllegalArgumentException("Lab view must be commands or menu (Paper only)");
    }
}
