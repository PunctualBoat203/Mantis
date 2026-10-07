package dev.punctualboat.mantis.minecraft;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

@GameTestHolder("mantis")
@PrefixGameTestTemplate(false)
public final class MantisGameTests {
    @GameTest(templateNamespace = "mantis", template = "empty", timeoutTicks = 600)
    public static void scriptedRecipesClockAndReload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReloadCheck check = new ReloadCheck(server, helper);
        helper.succeedWhen(check::tick);
    }

    private static final class ReloadCheck {
        private final MinecraftServer server;
        private final GameTestHelper helper;
        private final String original;
        private final long initialTick;
        private CompletableFuture<Void> reload;
        private int stage;

        ReloadCheck(MinecraftServer server, GameTestHelper helper) {
            this.server = server;
            this.helper = helper;
            try { original = Files.readString(ScriptDirectories.SERVER.resolve("smoke.js")); }
            catch (IOException error) { throw new IllegalStateException(error); }
            initialTick = ClockData.get(server).clock().ticks();
        }

        void tick() {
            if (stage == 0) {
                verifyRecipe();
                helper.assertTrue(ClockData.get(server).clock().scheduledTasks() == 1, "Expected one active script timer");
                write(original + "\nevents.on('recipes', () => recipes.custom('mantis:bad', { type: 'mantis:missing_serializer' }));\n");
                reload = server.reloadResources(server.getPackRepository().getSelectedIds());
                stage = 1;
                helper.assertTrue(false, "Waiting for rejected reload");
            }
            if (stage == 1) {
                helper.assertTrue(reload.isDone(), "Waiting for rejected reload");
                helper.assertTrue(reload.isCompletedExceptionally(), "An invalid scripted recipe must reject reload");
                verifyRecipe();
                helper.assertTrue(ClockData.get(server).clock().scheduledTasks() == 1, "Failed reload must keep the previous timer");
                write(original);
                reload = server.reloadResources(server.getPackRepository().getSelectedIds());
                stage = 2;
                helper.assertTrue(false, "Waiting for successful reload");
            }
            if (stage == 2) {
                helper.assertTrue(reload.isDone(), "Waiting for successful reload");
                helper.assertTrue(!reload.isCompletedExceptionally(), "Corrected scripts must reload successfully");
                verifyRecipe();
                var clock = ClockData.get(server).clock();
                helper.assertTrue(clock.ticks() > initialTick, "Internal clock must keep advancing through reloads");
                helper.assertTrue(clock.scheduledTasks() == 1, "Successful reload must replace the old timer");
                helper.assertTrue(clock.remaining("mantis:smoke") > 0, "Reload must preserve named cooldowns");
                CompoundTag saved = ClockData.get(server).save(new CompoundTag());
                helper.assertTrue(saved.getLong("ticks") == clock.ticks(), "World data must save the internal counter");
                helper.assertTrue(saved.getCompound("cooldowns").getLong("mantis:smoke") > clock.ticks(), "World data must save cooldown deadlines");
                stage = 3;
            }
        }

        private void verifyRecipe() {
            var recipe = server.getRecipeManager().byKey(new ResourceLocation("mantis:smoke"));
            helper.assertTrue(recipe.isPresent(), "Scripted recipe must load through RecipeManager");
            var result = recipe.orElseThrow().getResultItem(server.registryAccess());
            helper.assertTrue(result.is(Items.STICK) && result.getCount() == 4, "Nested recipe patch must reach the installed serializer");
        }

        private void write(String text) {
            try { Files.writeString(ScriptDirectories.SERVER.resolve("smoke.js"), text); }
            catch (IOException error) { throw new IllegalStateException(error); }
        }
    }
}
