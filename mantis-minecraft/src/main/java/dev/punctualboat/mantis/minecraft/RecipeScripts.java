package dev.punctualboat.mantis.minecraft;

import net.minecraftforge.common.crafting.conditions.ICondition;

public interface RecipeScripts {
    void mantis$conditionContext(ICondition.IContext context);
    PreparedScripts mantis$prepared();
}
