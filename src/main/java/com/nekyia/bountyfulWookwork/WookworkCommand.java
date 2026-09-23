package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * /bw reload - rereads config.yml and the archive.
 * /bw brush trees|clear - binds the held item to archived trees, or unbinds it.
 * /bw debug generation trees|off - replaces the trees of new chunks with archived ones.
 * /bw type [name] - lists the tree types, or makes one.
 * /bw trunkpos - marks the block a tree stands on; //trunkpos does the same.
 * /bw archive type creator [--size category] [--overwrite tree] - puts a tree into the archive.
 * /bw list [words] - names the archived trees, narrowed down by type, size or creator.
 * /bw duplicates - finds trees archived twice, turned or mirrored.
 * /bw resort tree size - files an archived tree under another size, renaming it.
 * /bw recreator tree creator - puts an archived tree under someone else's name.
 * /bw patreon name link - remembers an artist who is not a player here.
 * /bw layout categorized|terrain [trees] - lays the archive out on the tree map.
 * /bw forest preset [x z] - plants a forest preset on the middle of the map.
 */
final class WookworkCommand implements CommandExecutor, TabCompleter {

    private final TreeFelling felling;
    private final TreeBrush brush;
    private final DebugGeneration debug;
    private final TreeArchive archive;
    private final TreeLayout layout;
    private final TreeForest forest;
    private final TreeDuplicates duplicates;
    private final TrunkPosCommand trunkPos;

    WookworkCommand(TreeFelling felling, TreeBrush brush,
                    DebugGeneration debug, TreeArchive archive, TreeLayout layout,
                    TreeForest forest, TreeDuplicates duplicates, TrunkPosCommand trunkPos) {
        this.felling = felling;
        this.brush = brush;
        this.debug = debug;
        this.archive = archive;
        this.layout = layout;
        this.forest = forest;
        this.duplicates = duplicates;
        this.trunkPos = trunkPos;
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command,
                             @NonNull String label, @NonNull String @NonNull [] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            int archived = archive.reload();
            felling.reload();
            sender.sendMessage(Component.text("Loaded " + archived + " archived trees across "
                    + archive.types().size() + " tree types.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("trunkpos")) {
            if (sender instanceof Player player) {
                trunkPos.set(player);
            } else {
                sender.sendMessage(Component.text("Only players can mark a trunk.", NamedTextColor.RED));
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("archive")) {
            archive(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("recreator")) {
            recreator(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("resort")) {
            resort(sender, args);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("duplicates")) {
            if (duplicates.running()) {
                sender.sendMessage(Component.text("Already comparing.", NamedTextColor.RED));
            } else {
                duplicates.start(sender);
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            list(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("layout")) {
            layout(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("type")) {
            type(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("patreon")) {
            patreon(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("forest")) {
            forest(sender, args);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("brush")) {
            brush(sender, String.join("", Arrays.copyOfRange(args, 1, args.length)));
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("generation")) {
            generation(sender, args[2]);
            return true;
        }
        return false;
    }

    /** /bw brush trees|clear - trees written like /bw layout terrain takes them. */
    private void brush(CommandSender sender, String selection) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can hold a brush.", NamedTextColor.RED));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            player.sendMessage(Component.text("Hold the item to turn into a brush.", NamedTextColor.RED));
            return;
        }

        if (selection.equalsIgnoreCase("clear")) {
            brush.bind(item, null);
            player.sendMessage(Component.text("Removed the tree brush from this item.", NamedTextColor.GREEN));
            return;
        }
        String normalized = selection.toLowerCase(Locale.ROOT);
        int matching = TreeSelection.parse(normalized).matching(archive.entries()).size();
        if (matching == 0) {
            player.sendMessage(Component.text("No archived tree matches '" + normalized + "'.",
                    NamedTextColor.RED));
            return;
        }
        brush.bind(item, normalized);
        player.sendMessage(Component.text("This item is now a brush for " + normalized + " ("
                + matching + " trees). Right-click in creative to place them.", NamedTextColor.GREEN));
    }

    /** The flags /bw archive understands. */
    private static final List<String> FLAGS = List.of("--size", "--overwrite");

    /**
     * A command line split into its plain words and the flags it may carry. A flag may
     * stand anywhere, written as --size giant or --size=giant, so it stays out of the
     * way of a creator name made of several words.
     */
    private record Parsed(String[] words, @Nullable String size, @Nullable String overwrite) {

        static Parsed of(String[] args) {
            List<String> words = new ArrayList<>();
            Map<String, String> flags = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                int equals = argument.indexOf('=');
                String flag = (equals < 0 ? argument : argument.substring(0, equals)).toLowerCase(Locale.ROOT);
                if (FLAGS.contains(flag) && (equals >= 0 || i + 1 < args.length)) {
                    flags.put(flag, equals >= 0 ? argument.substring(equals + 1) : args[++i]);
                } else {
                    words.add(argument);
                }
            }
            return new Parsed(words.toArray(String[]::new), flags.get("--size"), flags.get("--overwrite"));
        }
    }

    /** /bw archive type creator [--size category] [--overwrite tree] - puts a tree into the archive. */
    private void archive(CommandSender sender, String[] rawArgs) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can archive a tree.", NamedTextColor.RED));
            return;
        }
        Parsed parsed = Parsed.of(rawArgs);
        String[] args = parsed.words();
        if (args.length < 3) {
            player.sendMessage(Component.text(
                    "Usage: /bw archive <type> <creator> [--size <category>] [--overwrite <tree>]",
                    NamedTextColor.RED));
            return;
        }

        // --overwrite puts the new blueprint in place of one already archived.
        TreeArchive.Entry replacing = null;
        if (parsed.overwrite() != null) {
            replacing = archive.entry(parsed.overwrite());
            if (replacing == null) {
                player.sendMessage(Component.text("No archived tree called '" + parsed.overwrite()
                        + "' to overwrite. /bw list shows them.", NamedTextColor.RED));
                return;
            }
        }

        // --size files the tree under a category its measurements would not put it in.
        TreeArchive.Category size = null;
        if (parsed.size() != null) {
            size = archive.category(parsed.size());
            if (size == null) {
                player.sendMessage(Component.text("Unknown size '" + parsed.size() + "'. Sizes: "
                        + archive.categories().stream().map(TreeArchive.Category::name)
                                .collect(Collectors.joining(", ")), NamedTextColor.RED));
                return;
            }
        }

        String type = args[1].toLowerCase(Locale.ROOT);
        if (!archive.hasType(type)) {
            player.sendMessage(Component.text("Unknown tree type '" + type
                    + "'. Make it with /bw type <name>. Tree types: "
                    + String.join(", ", archive.types()), NamedTextColor.RED));
            return;
        }
        // Everything after the type is the creator, so artist names may have spaces.
        String creatorName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        TreeArchive.Creator creator = archive.creator(creatorName);
        if (creator == null) {
            player.sendMessage(Component.text("Unknown creator '" + creatorName
                    + "'. Players have to have been on the server; register an artist once with "
                    + "/bw patreon <name> <link>.", NamedTextColor.RED));
            return;
        }
        BlockVector3 trunk = trunkPos.mark(player);
        if (trunk == null) {
            player.sendMessage(Component.text("Mark the block the tree stands on with //trunkpos first.",
                    NamedTextColor.RED));
            return;
        }

        Region region = selection(player);
        if (region == null) {
            return;
        }
        if (!region.contains(trunk)) {
            player.sendMessage(Component.text("The trunk mark is outside the selection.",
                    NamedTextColor.RED));
            return;
        }

        try {
            TreeArchive.Entry entry = archive.archive(player, type, creator, region, trunk, size, replacing);
            trunkPos.clear(player);
            String verb = replacing == null ? "Archived "
                    : replacing.id().equals(entry.id()) ? "Overwrote "
                    : "Replaced " + replacing.id() + " with ";
            player.sendMessage(Component.text(verb + entry.id() + ", a " + entry.type()
                    + " by " + creator.name() + (creator.patreon() ? " (patreon)" : "") + ".",
                    NamedTextColor.GREEN));

            // The size it was filed under, and what that means for its plot.
            TreeArchive.Category placed = archive.category(entry.category());
            String natural = archive.categoryFor(entry.width(), entry.height(), entry.length()).name();
            player.sendMessage(Component.text("Size " + entry.category()
                    + (size == null ? "" : " (forced; its measurements say " + natural + ")") + ": "
                    + entry.width() + " wide, " + entry.height() + " high, " + entry.length() + " long"
                    + (placed == null ? "" : ", on a " + placed.plotSize() + " block plot") + ".",
                    NamedTextColor.AQUA));

            // Forcing a size can put a tree on a plot it does not fit on.
            int side = Math.max(entry.width(), entry.length());
            if (size != null && side > size.plotSize()) {
                player.sendMessage(Component.text("It is " + side + " blocks wide, wider than the "
                        + size.plotSize() + " block " + size.name()
                        + " plot; it will reach into its neighbours on the tree map.",
                        NamedTextColor.YELLOW));
            }
        } catch (WorldEditException | IOException e) {
            player.sendMessage(Component.text("Could not archive: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    /** /bw resort tree size - files an archived tree under another size. */
    private void resort(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(Component.text("Usage: /bw resort <tree> <size>", NamedTextColor.RED));
            return;
        }
        TreeArchive.Entry entry = archive.entry(args[1]);
        if (entry == null) {
            sender.sendMessage(Component.text("No archived tree called '" + args[1]
                    + "'. /bw list shows them.", NamedTextColor.RED));
            return;
        }
        TreeArchive.Category category = archive.category(args[2]);
        if (category == null) {
            sender.sendMessage(Component.text("Unknown size '" + args[2] + "'. Sizes: "
                    + archive.categories().stream().map(TreeArchive.Category::name)
                            .collect(Collectors.joining(", ")), NamedTextColor.RED));
            return;
        }
        if (entry.category().equals(category.name())) {
            sender.sendMessage(Component.text(entry.id() + " is already filed as "
                    + category.name() + ".", NamedTextColor.YELLOW));
            return;
        }

        try {
            TreeArchive.Entry moved = archive.refile(entry, entry.type(), category, entry.creator());
            sender.sendMessage(Component.text("Refiled " + entry.id() + " as " + moved.id()
                    + ", on a " + category.plotSize() + " block plot. Run /bw layout again to"
                    + " move it on the tree map.", NamedTextColor.GREEN));
            int side = Math.max(moved.width(), moved.length());
            if (side > category.plotSize()) {
                sender.sendMessage(Component.text("It is " + side + " blocks wide, wider than that"
                        + " plot; it will reach into its neighbours.", NamedTextColor.YELLOW));
            }
        } catch (IOException e) {
            sender.sendMessage(Component.text("Could not refile it: " + e.getMessage(),
                    NamedTextColor.RED));
        }
    }

    /** /bw recreator tree creator - puts an archived tree under someone else's name. */
    private void recreator(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /bw recreator <tree> <creator>",
                    NamedTextColor.RED));
            return;
        }
        TreeArchive.Entry entry = archive.entry(args[1]);
        if (entry == null) {
            sender.sendMessage(Component.text("No archived tree called '" + args[1]
                    + "'. /bw list shows them.", NamedTextColor.RED));
            return;
        }
        // Everything after the tree is the creator, so artist names may have spaces.
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        TreeArchive.Creator creator = archive.creator(name);
        if (creator == null) {
            sender.sendMessage(Component.text("Unknown creator '" + name
                    + "'. Players have to have been on the server; register an artist once with "
                    + "/bw patreon <name> <link>.", NamedTextColor.RED));
            return;
        }

        try {
            TreeArchive.Entry moved = archive.refile(entry, entry.type(),
                    archive.categoryOf(entry), creator);
            sender.sendMessage(Component.text("Refiled " + entry.id() + " as " + moved.id()
                    + ", now by " + creator.name() + (creator.patreon() ? " (patreon)" : "") + ".",
                    NamedTextColor.GREEN));
        } catch (IOException e) {
            sender.sendMessage(Component.text("Could not refile it: " + e.getMessage(),
                    NamedTextColor.RED));
        }
    }

    /** How many archived trees are named before the rest are only counted. */
    private static final int LISTED = 40;

    /**
     * /bw list [words] - names the archived trees. Every word narrows the list down and
     * may be a tree type, a size, a creator or a piece of a name, so "/bw list birch"
     * and "/bw list birch giant snifferish" both read the way they are meant.
     */
    private void list(CommandSender sender, String[] args) {
        List<String> filters = Arrays.stream(args).skip(1)
                .map(word -> word.toLowerCase(Locale.ROOT)).toList();
        List<TreeArchive.Entry> found = archive.entries().stream()
                .filter(entry -> filters.stream().allMatch(filter -> matches(entry, filter)))
                .sorted(Comparator.comparing(TreeArchive.Entry::type, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(TreeArchive.Entry::height)
                        .thenComparing(TreeArchive.Entry::id))
                .toList();

        String what = filters.isEmpty() ? "" : " matching " + String.join(" ", filters);
        if (found.isEmpty()) {
            sender.sendMessage(Component.text(archive.entries().isEmpty()
                    ? "The archive is empty."
                    : "No archived tree" + what + ".", NamedTextColor.YELLOW));
            return;
        }

        sender.sendMessage(Component.text(found.size() + " archived tree"
                + (found.size() == 1 ? "" : "s") + what + ":", NamedTextColor.GREEN));
        for (TreeArchive.Entry entry : found.subList(0, Math.min(LISTED, found.size()))) {
            sender.sendMessage(Component.text("  " + entry.id() + "  " + entry.width() + "x"
                    + entry.height() + "x" + entry.length() + "  by " + entry.creator().name(),
                    NamedTextColor.GRAY));
        }
        if (found.size() > LISTED) {
            sender.sendMessage(Component.text("  ... and " + (found.size() - LISTED)
                    + " more; narrow it down with a type, a size or a creator.",
                    NamedTextColor.GRAY));
        }
    }

    /** The creator: an artist already known, or someone on the server. */
    private Stream<String> creators(CommandSender sender) {
        return Stream.concat(archive.patreons().keySet().stream(),
                sender.getServer().getOnlinePlayers().stream().map(Player::getName));
    }

    /** Everything /bw list understands as a word: the types, sizes and creators in use. */
    private Stream<String> filters() {
        return Stream.of(archive.types().stream(),
                        archive.categories().stream().map(TreeArchive.Category::name),
                        archive.entries().stream()
                                .map(entry -> entry.creator().name().toLowerCase(Locale.ROOT)))
                .flatMap(stream -> stream)
                .distinct();
    }

    /** Whether one word of /bw list says something true about this tree. */
    private static boolean matches(TreeArchive.Entry entry, String filter) {
        return entry.type().equals(filter)
                || entry.category().equals(filter)
                || entry.creator().name().toLowerCase(Locale.ROOT).contains(filter)
                || entry.id().contains(filter);
    }

    /**
     * /bw type - lists the tree types.
     * /bw type name - makes a new one.
     * /bw type remove name - drops one again while no archived tree is one.
     */
    private void type(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage(Component.text(archive.types().isEmpty()
                    ? "No tree types yet. Make one with /bw type <name>."
                    : "Tree types: " + String.join(", ", archive.types()), NamedTextColor.GREEN));
            return;
        }

        boolean remove = args[1].equalsIgnoreCase("remove");
        if (remove && args.length != 3) {
            sender.sendMessage(Component.text("Usage: /bw type remove <name>", NamedTextColor.RED));
            return;
        }
        String name = (remove ? args[2] : args[1]).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (name.isEmpty()) {
            sender.sendMessage(Component.text("A tree type is named in letters, digits and underscores.",
                    NamedTextColor.RED));
            return;
        }

        try {
            if (remove) {
                if (!archive.hasType(name)) {
                    sender.sendMessage(Component.text("There is no tree type '" + name + "'.",
                            NamedTextColor.RED));
                } else if (archive.removeType(name)) {
                    sender.sendMessage(Component.text("Dropped the tree type " + name + ".",
                            NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Archived trees are still " + name
                            + "; /bw list " + name + " shows them.", NamedTextColor.RED));
                }
                return;
            }
            if (!archive.createType(name)) {
                sender.sendMessage(Component.text("Tree type " + name + " is already there.",
                        NamedTextColor.YELLOW));
                return;
            }
        } catch (IOException e) {
            sender.sendMessage(Component.text("Could not save the tree types: " + e.getMessage(),
                    NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Made the tree type " + name
                + ". Archive trees as it with /bw archive " + name + " <creator>.", NamedTextColor.GREEN));
    }

    /** /bw patreon name link - remembers an artist, so archiving only needs the name. */
    private void patreon(CommandSender sender, String[] args) {
        if (args.length == 1) {
            Map<String, String> known = archive.patreons();
            if (known.isEmpty()) {
                sender.sendMessage(Component.text("No artists yet. /bw patreon <name> <link>",
                        NamedTextColor.YELLOW));
                return;
            }
            sender.sendMessage(Component.text("Artists:", NamedTextColor.GREEN));
            known.forEach((name, link) -> sender.sendMessage(
                    Component.text("  " + name + " - " + link, NamedTextColor.GRAY)));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /bw patreon <name> <link>", NamedTextColor.RED));
            return;
        }
        // The link is the last word; everything between may be a name with spaces.
        String link = args[args.length - 1];
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
        try {
            archive.patreon(name, link);
            sender.sendMessage(Component.text("Remembered " + name + " as " + link
                    + ". /bw archive <name> " + name + " is enough from now on.", NamedTextColor.GREEN));
        } catch (IOException e) {
            sender.sendMessage(Component.text("Could not save the artist: " + e.getMessage(),
                    NamedTextColor.RED));
        }
    }

    /** /bw layout [categorized|terrain] [trees] - puts the archive on the ground. */
    private void layout(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can lay out the archive.", NamedTextColor.RED));
            return;
        }
        if (layout.running()) {
            player.sendMessage(Component.text("Already laying out; /bw layout stop ends it.",
                    NamedTextColor.RED));
            return;
        }
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "categorized";
        if (mode.equals("stop")) {
            layout.stop();
            player.sendMessage(Component.text("Stopped.", NamedTextColor.GREEN));
            return;
        }
        if (!TreeLayout.modes().contains(mode)) {
            player.sendMessage(Component.text("Unknown layout '" + mode + "'. Layouts: "
                    + String.join(", ", TreeLayout.modes()), NamedTextColor.RED));
            return;
        }
        // The trees to use, written like WorldEdit blocks: birch,oak or 70%birch,30%oak.
        layout.start(player, mode, TreeSelection.parse(
                args.length > 2 ? String.join("", Arrays.copyOfRange(args, 2, args.length)) : null));
    }

    /**
     * /bw forest save name - saves the selection as a marker preset.
     * /bw forest preset [x z] - plants that preset on the middle of the map, or where told.
     */
    private void forest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can plant a forest.", NamedTextColor.RED));
            return;
        }
        if (args.length == 1) {
            player.sendMessage(Component.text("Usage: /bw forest <preset> [x z] | save <name> | stop"
                    + (forest.presets().isEmpty() ? "" : ". Presets: "
                    + String.join(", ", forest.presets())), NamedTextColor.RED));
            return;
        }
        if (args[1].equalsIgnoreCase("stop")) {
            forest.stop();
            player.sendMessage(Component.text("Stopped planting.", NamedTextColor.GREEN));
            return;
        }
        if (args[1].equalsIgnoreCase("save")) {
            if (args.length != 3) {
                player.sendMessage(Component.text("Usage: /bw forest save <name>", NamedTextColor.RED));
                return;
            }
            Region region = selection(player);
            if (region == null) {
                return;
            }
            try {
                forest.save(player, args[2], region);
                player.sendMessage(Component.text("Saved the selection as preset '" + args[2]
                        + "'. Every marker block in it becomes one tree.", NamedTextColor.GREEN));
            } catch (WorldEditException | IOException e) {
                player.sendMessage(Component.text("Could not save the preset: " + e.getMessage(),
                        NamedTextColor.RED));
            }
            return;
        }

        if (forest.running()) {
            player.sendMessage(Component.text("Already planting; /bw forest stop ends it.",
                    NamedTextColor.RED));
            return;
        }
        int[] centre = forest.centre();
        if (args.length == 4) {
            try {
                centre = new int[] {Integer.parseInt(args[2]), Integer.parseInt(args[3])};
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("The middle must be two whole numbers.",
                        NamedTextColor.RED));
                return;
            }
        } else if (args.length != 2) {
            player.sendMessage(Component.text("Usage: /bw forest <preset> [x z]", NamedTextColor.RED));
            return;
        }
        forest.start(player, args[1], centre[0], centre[1]);
    }

    /** The player's WorldEdit selection, or null after telling them to make one. */
    private @Nullable Region selection(Player player) {
        try {
            return WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player))
                    .getSelection(BukkitAdapter.adapt(player.getWorld()));
        } catch (IncompleteRegionException e) {
            player.sendMessage(Component.text("Select it with //pos1 and //pos2 first.",
                    NamedTextColor.RED));
            return null;
        }
    }

    /** Replaces the trees of newly generated chunks with archived ones, to look at forests. */
    private void generation(CommandSender sender, String type) {
        if (type.equalsIgnoreCase("off") || type.equalsIgnoreCase("none")) {
            int[] counts = debug.counts();
            debug.type(null, null);
            sender.sendMessage(Component.text("Newly generated chunks keep their vanilla trees again. "
                    + counts[0] + " trees replaced, " + counts[1] + " had no room, " + counts[2]
                    + " chunks skipped.", NamedTextColor.GREEN));
            return;
        }
        if (TreeSelection.parse(type).matching(archive.entries()).isEmpty()) {
            sender.sendMessage(Component.text("No archived tree matches '" + type + "'.",
                    NamedTextColor.RED));
            return;
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        debug.type(normalized, sender instanceof Player player ? player.getUniqueId() : null);
        sender.sendMessage(Component.text("Trees in newly generated chunks are now replaced with "
                + normalized + ". Fly into fresh land to see it; /bw debug generation off stops it.",
                NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command,
                                      @NonNull String label, @NonNull String @NonNull [] args) {
        Stream<String> options = switch (args.length) {
            case 1 -> Stream.of("reload", "brush", "debug", "type", "trunkpos", "archive", "list",
                    "duplicates", "resort", "recreator", "patreon", "layout", "forest");
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "brush" -> Stream.concat(Stream.of("clear"), filters());
                case "archive" -> archive.types().stream();
                // Only "remove": here a type is being named, usually a new one.
                case "type" -> Stream.of("remove");
                case "patreon" -> archive.patreons().keySet().stream();
                case "layout" -> Stream.concat(TreeLayout.modes().stream(), Stream.of("stop"));
                // The trees to lay out, written like WorldEdit blocks.
                case "forest" -> Stream.concat(forest.presets().stream(), Stream.of("save", "stop"));
                case "debug" -> Stream.of("generation");
                case "list" -> filters();
                case "resort", "recreator" -> archive.entries().stream().map(TreeArchive.Entry::id);
                default -> Stream.empty();
            };
            case 3 -> {
                if (args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("generation")) {
                    yield Stream.concat(Stream.of("off"), filters());
                }
                if (args[0].equalsIgnoreCase("type") && args[1].equalsIgnoreCase("remove")) {
                    yield archive.types().stream();
                }
                if (args[0].equalsIgnoreCase("list")) {
                    yield filters();
                }
                if (args[0].equalsIgnoreCase("resort")) {
                    yield archive.categories().stream().map(TreeArchive.Category::name);
                }
                if (args[0].equalsIgnoreCase("recreator")) {
                    yield creators(sender);
                }
                if (args[0].equalsIgnoreCase("layout")) {
                    yield filters();
                }
                yield args[0].equalsIgnoreCase("archive") ? creators(sender) : Stream.empty();
            }
            default -> args[0].equalsIgnoreCase("list") ? filters() : Stream.empty();
        };
        // --size may stand anywhere, so it is offered wherever it is being typed.
        if (args.length > 1 && args[args.length - 2].equalsIgnoreCase("--size")) {
            options = archive.categories().stream().map(TreeArchive.Category::name);
        } else if (args.length > 1 && args[args.length - 2].equalsIgnoreCase("--overwrite")) {
            options = archive.entries().stream().map(TreeArchive.Entry::id);
        } else if (args[args.length - 1].startsWith("-") && args[0].equalsIgnoreCase("archive")) {
            options = FLAGS.stream();
        }

        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.filter(option -> option.startsWith(prefix)).toList();
    }
}
