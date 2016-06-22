package me.lucko.luckperms.utils;

import com.google.gson.reflect.TypeToken;
import lombok.Getter;
import lombok.Setter;
import me.lucko.luckperms.LuckPermsPlugin;
import me.lucko.luckperms.exceptions.ObjectAlreadyHasException;
import me.lucko.luckperms.exceptions.ObjectLacksPermissionException;
import me.lucko.luckperms.groups.Group;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents an object that can hold permissions
 * For example a User or a Group
 */
@Getter
public abstract class PermissionObject {

    /**
     * The UUID of the user / name of the group.
     * Used to prevent circular inheritance issues
     */
    private final String objectName;

    /**
     * Reference to the main plugin instance
     */
    private final LuckPermsPlugin plugin;

    /**
     * If false, only permissions specific to the server are applied
     */
    @Setter
    private boolean includeGlobalPermissions;

    /**
     * The user/group's permissions
     */
    @Setter
    private Map<String, Boolean> nodes = new HashMap<>();

    public PermissionObject(LuckPermsPlugin plugin, String objectName) {
        this.objectName = objectName;
        this.plugin = plugin;
        this.includeGlobalPermissions = plugin.getConfiguration().getIncludeGlobalPerms();
    }

    /**
     * Checks to see if the object has a certain permission
     * @param node The permission node
     * @param b If the node is true/false(negated)
     * @return true if the user has the permission
     */
    public boolean hasPermission(String node, Boolean b) {
        if (node.startsWith("global/")) node = node.replace("global/", "");
        if (b) {
            return getNodes().containsKey(node) && getNodes().get(node);
        }
        return getNodes().containsKey(node) && !getNodes().get(node);
    }

    /**
     * Checks to see the the object has a permission on a certain server
     * @param node The permission node
     * @param b If the node is true/false(negated)
     * @param server The server
     * @return true if the user has the permission
     */
    public boolean hasPermission(String node, Boolean b, String server) {
        return hasPermission(server + "/" + node, b);
    }

    /**
     * Sets a permission for the object
     * @param node The node to be set
     * @param value What to set the node to - true/false(negated)
     * @throws ObjectAlreadyHasException if the object already has the permission
     */
    public void setPermission(String node, Boolean value) throws ObjectAlreadyHasException {
        if (node.startsWith("global/")) node = node.replace("global/", "");
        if (hasPermission(node, value)) {
            throw new ObjectAlreadyHasException();
        }
        getNodes().put(node, value);
    }

    /**
     * Sets a permission for the object
     * @param node The node to set
     * @param value What to set the node to - true/false(negated)
     * @param server The server to set the permission on
     * @throws ObjectAlreadyHasException if the object already has the permission
     */
    public void setPermission(String node, Boolean value, String server) throws ObjectAlreadyHasException {
        setPermission(server + "/" + node, value);
    }

    /**
     * Unsets a permission for the object
     * @param node The node to be unset
     * @throws ObjectLacksPermissionException if the node wasn't already set
     */
    public void unsetPermission(String node) throws ObjectLacksPermissionException {
        if (node.startsWith("global/")) node = node.replace("global/", "");
        if (!getNodes().containsKey(node)) {
            throw new ObjectLacksPermissionException();
        }

        getNodes().remove(node);
    }

    /**
     * Unsets a permission for the object
     * @param node The node to be unset
     * @param server The server to unset the node on
     * @throws ObjectLacksPermissionException if the node wasn't already set
     */
    public void unsetPermission(String node, String server) throws ObjectLacksPermissionException {
        unsetPermission(server + "/" + node);
    }

    /**
     * Gets the permissions and inherited permissions that apply to a specific server
     * @param server The server to get nodes for
     * @param excludedGroups Groups that shouldn't be inherited (to prevent circular inheritance issues)
     * @return a {@link Map} of the permissions
     */
    public Map<String, Boolean> getLocalPermissions(String server, List<String> excludedGroups) {
        return getPermissions(server, excludedGroups, includeGlobalPermissions);

    }

    private Map<String, Boolean> getPermissions(String server, List<String> excludedGroups, boolean includeGlobal) {
        if (excludedGroups == null) {
            excludedGroups = new ArrayList<>();
        }

        excludedGroups.add(getObjectName());
        Map<String, Boolean> perms = new HashMap<>();

        if (server == null || server.equals("")) {
            server = "global";
        }

        /*
        Priority:

        1. server specific nodes
        2. user nodes
        3. server specific group nodes
        4. group nodes
        */

        final Map<String, Boolean> serverSpecificNodes = new HashMap<>();
        final Map<String, Boolean> userNodes = new HashMap<>();
        final Map<String, Boolean> serverSpecificGroups = new HashMap<>();
        final Map<String, Boolean> groupNodes = new HashMap<>();

        // Sorts the permissions and puts them into a priority order
        for (Map.Entry<String, Boolean> node : getNodes().entrySet()) {
            serverSpecific:
            if (node.getKey().contains("/")) {
                String[] parts = node.getKey().split("\\/", 2);

                if (parts[0].equalsIgnoreCase("global")) {
                    // REGULAR
                    break serverSpecific;
                }

                if (!parts[0].equalsIgnoreCase(server)) {
                    // SERVER SPECIFIC BUT DOES NOT APPLY
                    continue;
                }

                if (parts[1].matches("luckperms\\.group\\..*")) {
                    // SERVER SPECIFIC AND GROUP
                    serverSpecificGroups.put(node.getKey(), node.getValue());
                    continue;
                }

                // SERVER SPECIFIC
                serverSpecificNodes.put(node.getKey(), node.getValue());
                continue;
            }

            // Skip adding global permissions if they are not requested
            if (!includeGlobal) continue;

            if (node.getKey().matches("luckperms\\.group\\..*")) {
                // GROUP
                groupNodes.put(node.getKey(), node.getValue());
                continue;
            }

            // JUST NORMAL
            userNodes.put(node.getKey(), node.getValue());
        }

        // If a group is negated at a higher priority, the group should not then be applied at a lower priority
        serverSpecificGroups.entrySet().stream().filter(node -> !node.getValue()).forEach(node -> {
            groupNodes.remove(node.getKey());
            groupNodes.remove(node.getKey().split("\\/", 2)[1]);
        });

        // Apply lowest priority: groupNodes
        for (Map.Entry<String, Boolean> groupNode : groupNodes.entrySet()) {
            // Add the actual group perm node, so other plugins can hook
            perms.put(groupNode.getKey(), groupNode.getValue());


            // Don't add negated groups
            if (!groupNode.getValue()) continue;

            String groupName = groupNode.getKey().split("\\.", 3)[2];
            if (!excludedGroups.contains(groupName)) {
                Group group = plugin.getGroupManager().getGroup(groupName);
                if (group != null) {
                    perms.putAll(group.getLocalPermissions(server, excludedGroups));
                } else {
                    plugin.getLogger().warning("Error whilst refreshing the permissions of '" + objectName + "'." +
                            "\n The group '" + groupName + "' is not loaded.");
                }
            }
        }

        // Apply next priority: serverSpecificGroups
        for (Map.Entry<String, Boolean> groupNode : serverSpecificGroups.entrySet()) {
            final String rawNode = groupNode.getKey().split("\\/")[1];

            // Add the actual group perm node, so other plugins can hook
            perms.put(rawNode, groupNode.getValue());

            // Don't add negated groups
            if (!groupNode.getValue()) continue;

            String groupName = rawNode.split("\\.", 3)[2];
            if (!excludedGroups.contains(groupName)) {
                Group group = plugin.getGroupManager().getGroup(groupName);
                if (group != null) {
                    perms.putAll(group.getLocalPermissions(server, excludedGroups));
                } else {
                    plugin.getLogger().warning("Error whilst refreshing the permissions of '" + objectName + "'." +
                            "\n The group '" + groupName + "' is not loaded.");
                }
            }
        }

        // Apply next priority: userNodes
        perms.putAll(userNodes);

        // Apply highest priority: serverSpecificNodes
        for (Map.Entry<String, Boolean> node : serverSpecificNodes.entrySet()) {
            final String rawNode = node.getKey().split("\\/")[1];
            perms.put(rawNode, node.getValue());
        }

        return perms;
    }

    /**
     * Loads serialised nodes into the object
     * @param json The json data to be loaded
     */
    public void loadNodes(String json) {
        nodes.putAll(plugin.getGson().fromJson(json, new TypeToken<Map<String, Boolean>>(){}.getType()));
    }

    /**
     * Serialize the nodes in the object to be saved in the datastore
     * @return A json string
     */
    public String serializeNodes() {
        return plugin.getGson().toJson(nodes);
    }
}
