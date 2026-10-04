package org.example;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

/**
 * Prints a confirmation message to whoever runs the command.
 *
 * <p>No permission is required: {@code AbstractCommand.hasPermission} returns true while no
 * permission query has been set, so both players and the server console can run it.
 */
public final class TestPluginCommand extends CommandBase {

    public TestPluginCommand() {
        super("testplugin", "Checks that the plugin template works");
    }

    @Override
    protected void executeSync(CommandContext context) {
        context.sendMessage(Message.raw("Plugin template works!"));
    }
}
