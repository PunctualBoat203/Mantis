package dev.punctualboat.mantis.minecraft;

import net.minecraftforge.common.crafting.conditions.ICondition;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

public interface RecipeScripts {
    void mantis$conditionContext(ICondition.IContext context);
    void mantis$commandDispatcher(CommandDispatcher<CommandSourceStack> dispatcher);
    PreparedScripts mantis$prepared();
}
