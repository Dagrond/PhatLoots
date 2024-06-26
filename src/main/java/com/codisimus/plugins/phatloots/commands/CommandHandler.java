package com.codisimus.plugins.phatloots.commands;

import com.codisimus.plugins.phatloots.PhatLoots;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Properties;
import java.util.TreeSet;
import java.util.logging.Level;

public class CommandHandler implements CommandExecutor {

    private enum ParameterType {
        STRING, INT, DOUBLE, BOOLEAN, MATERIAL, PLAYER, OFFLINEPLAYER,
        WORLD, PHATLOOT;

        public static ParameterType getType(Class parameter) {
            try {
                return ParameterType.valueOf(parameter.getSimpleName().toUpperCase());
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private static final Comparator<Method> METHOD_COMPARATOR = Comparator.comparingDouble(method ->
            method.getAnnotation(CodCommand.class).weight());

    private static final Comparator<CodCommand> CODCOMMAND_COMPARATOR = Comparator.comparingDouble(CodCommand::weight);

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface CodCommand {
        String command();
        String subcommand() default "";
        double weight() default 0;
        String[] aliases() default {};
        String[] usage() default {};
        String permission() default "";
        int minArgs() default 0;
        int maxArgs() default -1;
    }

    private final boolean groupedCommands;
    private final String parentCommand;
    private final TreeSet<CodCommand> metas = new TreeSet<>(CODCOMMAND_COMPARATOR);
    private final HashMap<CodCommand, TreeSet<Method>> methods = new HashMap<>();
    private final Properties aliases = new Properties();
    private final JavaPlugin plugin;

    public CommandHandler(JavaPlugin plugin, String commandGroup, String description) {
        this.plugin = plugin;
        groupedCommands = true;
        parentCommand = commandGroup;
        CommandInjector.inject(commandGroup, description, command -> {
            if (command == null) {
                plugin.getLogger().warning("CodCommand " + commandGroup + " was not found in plugin.yml");
            } else {
                command.setExecutor(this);
            }
        });
    }

    /**
     * Registers all command methods from the given class
     *
     * @param commandClass The given Class
     */
    public void registerCommands(Class<?> commandClass) {
        //Find each CodCommand in the class
        for (Method method : commandClass.getDeclaredMethods()) {
            if (method.getAnnotation(CodCommand.class) == null) {
                //Not a CodCommand
                continue;
            }

            //Verify the command returns a boolean
            if (method.getReturnType() == Boolean.class) {
                plugin.getLogger().warning("CodCommand " + method.getName() + " does not return a boolean");
                continue;
            }

            CodCommand annotation = method.getAnnotation(CodCommand.class);

            if (!groupedCommands) {
                PluginCommand command = plugin.getCommand(annotation.command());
                if (command == null) {
                    plugin.getLogger().warning("CodCommand " + method.getName() + " was not found in plugin.yml");
                    continue;
                }
                command.setExecutor(this);
                command.setAliases(Arrays.asList(annotation.aliases()));
            } else {
                for (String alias : annotation.aliases()) {
                    aliases.setProperty(alias, annotation.command());
                }
            }

            CodCommand meta = findMeta(annotation);
            if (meta == null || CODCOMMAND_COMPARATOR.compare(annotation, meta) < 0) {
                //This new (or first) meta has highest priority and should thus be the main one
                TreeSet<Method> treeSet;
                if (meta == null) {
                    treeSet = new TreeSet<>(METHOD_COMPARATOR);
                } else {
                    metas.remove(meta);
                    treeSet = methods.remove(meta);
                }
                meta = annotation;
                metas.add(meta);
                methods.put(meta, treeSet);
            }
            methods.get(meta).add(method);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (!groupedCommands) {
            handleCommand(sender, findMeta(command.getName(), ""), args);
            return true;
        }
        //Command is part of a group of commands

        if (args.length == 0) {
            //No subcommand was added
            CodCommand meta = findMeta("&none", null);
            if (meta != null) {
                handleCommand(sender, meta, new String[0]);
            } else {
                displayHelpPage(sender, 1);
            }
            return true;
        }

        String subcommand = aliases.containsKey(args[0])
                ? aliases.getProperty(args[0])
                : args[0];
        String arg1 = args.length > 1 ? args[1] : null;
        CodCommand meta = findMeta(subcommand, arg1);
        if (meta != null) {
            int index = meta.command().equals("&variable") ? 0 : 1;
            if (!meta.subcommand().isEmpty()) {
                index++;
            }
            handleCommand(sender, meta, Arrays.copyOfRange(args, index, args.length));
        } else if (subcommand.equals("help")) { //Default 'help' subcommand
            subcommand = arg1 != null && aliases.containsKey(arg1)
                    ? aliases.getProperty(arg1)
                    : arg1;
            switch (args.length) {
                case 2:
                    // Display help pages
                    try {
                        displayHelpPage(sender, Integer.parseInt(args[1]));
                        return true;
                    } catch (NumberFormatException ex) {
                        /* do nothing */
                    }

                    meta = findMeta(subcommand, null);
                    break;
                case 3:
                    meta = findMeta(subcommand, args[2]);
                    break;
                default:
                    break;
            }

            if (meta != null) {
                displayUsage(sender, meta);
            } else {
                displayHelpPage(sender, 1);
            }
        } else { //Invalid command
            sender.sendMessage("§6" + subcommand + "§4 is not a valid command");
            displayHelpPage(sender, 1);
        }
        return true;
    }

    /**
     * Discovers the correct method to invoke for the given command
     *
     * @param sender The CommandSender who is executing the command
     * @param meta The command which was sent
     * @param args The arguments which were sent with the command
     */
    private void handleCommand(CommandSender sender, CodCommand meta, String[] args) {
        try {
            //Check for permissions
            if (!meta.permission().isEmpty() && !sender.hasPermission(meta.permission())) {
                sender.sendMessage("§4You don't have permission to do that");
                return;
            }

            //Iterate through each method of the command to find one which has matching parameters
            for (Method method : methods.get(meta)) {
                Class[] requestedParameters = method.getParameterTypes();
                Object[] parameters = new Object[requestedParameters.length];
                int argumentCount = args.length;

                //Check the type of command sender
                if (requestedParameters[0] == Player.class && !(sender instanceof Player)) {
                    //Invalid CommandSender type
                    continue;
                }
                parameters[0] = sender;

                //Check if there is a variable parameter (String[])
                if (requestedParameters[requestedParameters.length - 1] == String[].class) {
                    argumentCount = requestedParameters.length - 2;
                    int variableParameterCount = args.length - argumentCount;

                    if (variableParameterCount < meta.minArgs()) {
                        //Too few commands
                        continue;
                    }

                    if (meta.maxArgs() != -1 && variableParameterCount > meta.maxArgs()) {
                        //Too many commands
                        continue;
                    }

                    String[] variableParameters = variableParameterCount == 0 ? new String[0] : Arrays.copyOfRange(args, argumentCount, args.length);
                    parameters[parameters.length - 1] = variableParameters;
                } else if (requestedParameters.length - 1 != argumentCount) {
                    //Invalid amount of commands
                    continue;
                }

                //Loop through each argument to see if they match the parameters
                boolean match = true;
                for (int i = 0; i < argumentCount; i++) {
                    Object o = validate(args[i], requestedParameters[i + 1]);
                    if (o == null) {
                        //Invalid parameter type
                        match = false;
                        break;
                    } else {
                        //Place the returned object in the array of parameters
                        parameters[i + 1] = o;
                    }
                }

                if (match) {
                    //The parameters fit, call the method
                    if (!(Boolean) method.invoke(method.getDeclaringClass().newInstance(), parameters)) {
                        //The method wishes the usage of the command to be displayed
                        displayUsage(sender, meta);
                    }
                    return;
                }
            }

            displayUsage(sender, meta);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            sender.sendMessage("§4An error occured while trying to perform this command. Please notify a server administrator.");
            plugin.getLogger().log(Level.SEVERE, "Error occured when executing command " + getCommand(meta), ex);
        }
    }

    /**
     * Verifies that the argument is of the given class
     *
     * @param argument The argument that was given
     * @param parameter The Class that the argument should be
     * @return true if the argument correctly represents the given Class
     */
    private Object validate(String argument, Class parameter) {
        try {
            ParameterType type = ParameterType.getType(parameter);

            switch (type) {
                case STRING:
                    return argument;
                case INT:
                    return Integer.parseInt(argument);
                case DOUBLE:
                    return Double.parseDouble(argument);
                case BOOLEAN:
                    switch (argument) {
                        case "true":
                        case "on":
                        case "yes":
                            return true;
                        case "false":
                        case "off":
                        case "no":
                            return false;
                        default:
                            return null;
                    }
                case MATERIAL:
                    return Material.matchMaterial(argument);
                case PLAYER:
                    return Bukkit.getPlayer(argument);
                case OFFLINEPLAYER:
                    return Bukkit.getOfflinePlayer(argument);
                case WORLD:
                    return Bukkit.getWorld(argument);
                case PHATLOOT:
                    return PhatLoots.getPhatLoot(argument);
                default:
                    return null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Returns the meta of the given command
     *
     * @param command The command to retrieve the meta for
     * @param subcommand The subcommand if any
     * @return The CodCommand or null if none was found
     */
    private CodCommand findMeta(String command, String subcommand) {
        for (CodCommand meta : metas) {
            //Check if the commands match
            if (meta.command().equals(command)
                    || (meta.command().equals("&variable")
                    && !command.equals("&none")
                    && !command.equals("help"))) {
                //Check if the subcommands match
                if (meta.subcommand().isEmpty() || meta.subcommand().equals(subcommand)) {
                    return meta;
                }
            }
        }
        return null;
    }

    /**
     * Returns the meta of the given command
     *
     * @param annotation The command to retrieve the meta for
     * @return The CodCommand or null if none was found
     */
    private CodCommand findMeta(CodCommand annotation) {
        for (CodCommand meta : metas) {
            if (meta.command().equals(annotation.command())
                    && meta.subcommand().equals(annotation.subcommand())) {
                return meta;
            }
        }
        return null;
    }

    /**
     * Displays the help page for grouped commands
     *
     * @param sender The sender to display the help page to
     * @param page The page to display
     */
    private void displayHelpPage(CommandSender sender, int page) {
        sender.sendMessage("§1Sub commands of §6/" + parentCommand + "§1:");
        sender.sendMessage("§2/" + parentCommand + " help §f<§6command§f> =§b Display the usage of a sub command.");

        boolean resultsFound = false;
        int displaySize = page * 10;
        for (int i = displaySize - 10; i < displaySize; i++) {
            if (i >= metas.size())
                continue;

            CodCommand meta = metas.toArray(new CodCommand[0])[i];
            displayOneLiner(sender, meta);
            resultsFound = true;
        }

        if (!resultsFound)
            sender.sendMessage("§4Could not find any results for page §6" + page + "§4.");
        else if (metas.size() >= displaySize)
            sender.sendMessage("§6Use §b/loot help " + (page + 1) + "§6 to view the next page of results.");
    }

    /**
     * Displays a one line usage of the given command
     *
     * @param sender The sender to display the command usage to
     * @param meta The given CodCommand
     */
    private void displayOneLiner(CommandSender sender, CodCommand meta) {
        TextComponent component = new TextComponent();
        String cmd = getCommand(meta);
        if (meta.usage().length == 1) {
            TextComponent command = new TextComponent(meta.usage()[0].replace("<command>", cmd));
            command.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[] {new TextComponent("Click to run.")}));
            command.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
            sender.spigot().sendMessage(command);
        } else {
            TextComponent command = new TextComponent(cmd);
            command.setColor(ChatColor.DARK_GREEN);
            command.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[] {new TextComponent("Click to run.")}));
            command.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
            component.addExtra(command);

            for (BaseComponent comp : TextComponent.fromLegacyText(ChatColor.RESET + " = " + ChatColor.AQUA + "'/")) {
                component.addExtra(comp);
            }

            TextComponent desc = new TextComponent(parentCommand + " help " + meta.command() + "'");
            desc.setColor(ChatColor.AQUA);

            TextComponent[] components = new TextComponent[meta.usage().length];
            for (int i = 0; i < meta.usage().length; i++) {
                String nextLine = "\n";
                if (i == 0)
                    nextLine = "";

                components[i] = new TextComponent(nextLine + meta.usage()[i].replace("<command>", cmd));
            }

            desc.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, components));
            component.addExtra(desc);

            for (BaseComponent comp : TextComponent.fromLegacyText(ChatColor.AQUA + " for full usage.")) {
                component.addExtra(comp);
            }

            sender.spigot().sendMessage(component);
        }
    }

    /**
     * Displays the usage of the given command
     *
     * @param sender The sender to display the command usage to
     * @param meta The given CodCommand
     */
    private void displayUsage(CommandSender sender, CodCommand meta) {
        String cmd = getCommand(meta);
        for (String string : meta.usage()) {
            sender.sendMessage(string.replace("<command>", cmd));
        }
    }

    /**
     * Returns the correctly formatted command
     *
     * @param meta The requested CodCommand
     * @return The command including '/' and any parent command
     */
    private String getCommand(CodCommand meta) {
        StringBuilder sb = new StringBuilder();
        sb.append('/');
        if (groupedCommands) {
            sb.append(parentCommand);
        }
        if (!meta.command().startsWith("&")) {
            sb.append(' ');
            sb.append(meta.command());
        }
        if (!meta.subcommand().isEmpty()) {
            sb.append(' ');
            sb.append(meta.subcommand());
        }
        return sb.toString();
    }
}