package at.esoren.vystormpaddock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Race courses for time trials on horseback: a start, checkpoints in order and a finish (the last point), plus every
 * rider's best time. All courses live in one {@code races.yml}; a broken file is never overwritten.
 */
public final class Races {
    /** A rider's best time on a course. */
    public record Time(UUID player, String name, long millis, long at) {}

    /** One course. Points are {x, y, z}; the first is the start, the last the finish. */
    public static final class Course {
        private final String name, world;
        private double radius;
        private final List<double[]> points = new ArrayList<>();
        private final Map<UUID, Time> times = new LinkedHashMap<>();

        public Course(String name, String world, double radius) {
            this.name = name;
            this.world = world;
            this.radius = radius;
        }

        public String name() { return name; }
        public String world() { return world; }
        public double radius() { return radius; }
        public void radius(double radius) { this.radius = radius; }
        public List<double[]> points() { return points; }
        public Map<UUID, Time> times() { return times; }
        /** At least a start and a finish. */
        public boolean ready() { return points.size() >= 2; }
    }

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");

    private final Path file;
    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;
    private final Map<String, Course> courses = new LinkedHashMap<>();
    private boolean broken;

    /** @param files Core's file part, {@code api.data().files()} */
    public Races(Path file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) { this.file = file; this.files = files; }

    public Collection<Course> courses() { return courses.values(); }

    public Course get(String name) { return name == null ? null : courses.get(name.toLowerCase(Locale.ROOT)); }

    public Course create(String name, String world, double radius) {
        Course course = new Course(name, world, radius);
        courses.put(name.toLowerCase(Locale.ROOT), course);
        return course;
    }

    public boolean delete(String name) { return courses.remove(name.toLowerCase(Locale.ROOT)) != null; }

    /** True if the file could not be read; saving is refused so the admin can repair it by hand. */
    public boolean broken() { return broken; }

    public void load() throws IOException {
        courses.clear();
        broken = false;
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(Files.readString(file)); }
        catch (InvalidConfigurationException failure) {
            broken = true;
            throw new IOException("Race file " + file + " is broken", failure);
        }
        ConfigurationSection section = yaml.getConfigurationSection("courses");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null || !NAME.matcher(key).matches()) continue;
            Course course = create(entry.getString("name", key), entry.getString("world", "world"), entry.getDouble("radius", 4));
            for (String raw : entry.getStringList("points")) {
                double[] point = point(raw);
                if (point != null) course.points().add(point);
            }
            ConfigurationSection times = entry.getConfigurationSection("times");
            if (times == null) continue;
            for (String id : times.getKeys(false)) {
                try {
                    UUID player = UUID.fromString(id);
                    long millis = times.getLong(id + ".millis");
                    if (millis > 0) course.times().put(player, new Time(player, times.getString(id + ".name", "?"), millis, times.getLong(id + ".at")));
                } catch (IllegalArgumentException skip) {
                    // a broken id; it is dropped with the next save
                }
            }
        }
    }

    public void save() throws IOException {
        if (broken) throw new IOException("Not overwriting the broken race file " + file);
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", 1);
        yaml.createSection("courses");
        for (Map.Entry<String, Course> entry : courses.entrySet()) {
            Course course = entry.getValue();
            String base = "courses." + entry.getKey();
            yaml.set(base + ".name", course.name());
            yaml.set(base + ".world", course.world());
            yaml.set(base + ".radius", course.radius());
            List<String> points = new ArrayList<>();
            for (double[] point : course.points()) points.add(format(point));
            yaml.set(base + ".points", points);
            for (Time time : course.times().values()) {
                yaml.set(base + ".times." + time.player() + ".name", time.name());
                yaml.set(base + ".times." + time.player() + ".millis", time.millis());
                yaml.set(base + ".times." + time.player() + ".at", time.at());
            }
        }
        files.write(file, yaml);
    }

    static String format(double[] point) { return String.format(Locale.ROOT, "%.1f %.1f %.1f", point[0], point[1], point[2]); }

    static double[] point(String raw) {
        String[] parts = raw == null ? new String[0] : raw.trim().split("\\s+");
        if (parts.length != 3) return null;
        try { return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])}; }
        catch (NumberFormatException broken) { return null; }
    }

    // ------------------------------------------------------------------ race logic

    /** Inside a checkpoint: within {@code radius} horizontally and a few blocks up or down (horses jump). */
    static boolean reached(double[] point, double radius, double x, double y, double z) {
        double dx = point[0] - x, dz = point[2] - z;
        return dx * dx + dz * dz <= radius * radius && Math.abs(point[1] - y) <= Math.max(3, radius);
    }

    /** Next checkpoint index after this position: {@code next + 1} if reached, else unchanged. */
    static int advance(Course course, int next, double x, double y, double z) {
        return next < course.points().size() && reached(course.points().get(next), course.radius(), x, y, z) ? next + 1 : next;
    }

    /** The ready course whose start contains this position, or null. */
    static Course startAt(Collection<Course> courses, String world, double x, double y, double z) {
        for (Course course : courses)
            if (course.ready() && course.world().equals(world) && reached(course.points().getFirst(), course.radius(), x, y, z)) return course;
        return null;
    }

    /** Stores a finished run; true if it is the rider's new personal best. */
    static boolean record(Course course, UUID player, String name, long millis, long now) {
        Time old = course.times().get(player);
        if (old != null && old.millis() <= millis) return false;
        course.times().put(player, new Time(player, name, millis, now));
        return true;
    }

    /** Fastest first; equal times: who set it first. */
    static List<Time> top(Course course, int limit) {
        List<Time> out = new ArrayList<>(course.times().values());
        out.sort((a, b) -> a.millis() != b.millis() ? Long.compare(a.millis(), b.millis()) : Long.compare(a.at(), b.at()));
        return out.subList(0, Math.min(limit, out.size()));
    }

    /** Place of a rider on the course (1 = record), 0 without a time. */
    static int rank(Course course, UUID player) {
        List<Time> all = top(course, Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) if (all.get(i).player().equals(player)) return i + 1;
        return 0;
    }

    /** "1:02.35" (minutes:seconds.hundredths). */
    static String time(long millis) {
        long hundredths = millis / 10;
        return String.format(Locale.ROOT, "%d:%02d.%02d", hundredths / 6000, hundredths / 100 % 60, hundredths % 100);
    }
}
