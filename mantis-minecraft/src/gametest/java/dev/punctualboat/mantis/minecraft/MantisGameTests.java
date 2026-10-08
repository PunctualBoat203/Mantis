package dev.punctualboat.mantis.minecraft;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.common.crafting.conditions.ICondition;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.flag.FeatureFlags;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.*;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@GameTestHolder("mantis")
@PrefixGameTestTemplate(false)
public final class MantisGameTests {
    @GameTest(templateNamespace = "mantis", template = "empty")
    public static void commandDeclarationsValidateAndRelease(GameTestHelper helper) {
        ScriptCommands[] commands = new ScriptCommands[1];
        try (var session = new dev.punctualboat.mantis.runtime.ScriptSession(Mantis.engine(), Map.of(), null,
                text -> {}, error -> { throw new IllegalStateException(error); }, registrar -> commands[0] = new ScriptCommands(registrar))) {
            var handler = session.context().evaluate("handler", "() => 1");
            for (String options : List.of("({permission:1.5})", "({permission:5})", "({unknown:true})",
                    "({arguments:[{name:'value',type:'integer',min:3,max:1}]})",
                    "({arguments:[{name:'value',type:'word',optional:true},{name:'other',type:'word'}]})",
                    "({arguments:[{name:'value',type:'greedy'},{name:'other',type:'word'}]})",
                    "({arguments:[{name:'value',type:'boolean',suggestions:['true']}]})",
                    "({arguments:[{name:'value',type:'word'},{name:'value',type:'word'}]})")) {
                boolean rejected = false;
                try { commands[0].register("mantis:validation", session.context().evaluate("options", options), handler); }
                catch (IllegalArgumentException expected) { rejected = true; }
                helper.assertTrue(rejected && session.ownedResources() == 0, "Invalid declarations must reject without retaining callbacks: " + options);
            }
            var options = session.context().evaluate("options", "({permission:0})");
            commands[0].register("mantis:validation", options, handler);
            boolean rejected = false;
            try { commands[0].register("mantis:validation", options, handler); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected && session.ownedResources() == 1, "Duplicate commands must not retain a second callback");
            commands[0].freeze(); rejected = false;
            try { commands[0].register("mantis:late", options, handler); } catch (IllegalStateException expected) { rejected = true; }
            helper.assertTrue(rejected, "Late command declarations must reject after preparing");
            var dispatcher = new CommandDispatcher<CommandSourceStack>(); commands[0].install(dispatcher);
            helper.assertTrue(dispatcher.getRoot().getChild("mantis:validation").canUse(helper.getLevel().getServer().createCommandSourceStack()), "Valid declarations must install");
            session.close();
            helper.assertTrue(session.ownedResources() == 0 && !dispatcher.getRoot().getChild("mantis:validation").canUse(helper.getLevel().getServer().createCommandSourceStack()), "Closing a generation must invalidate retained command nodes");
        }
        helper.succeed();
    }
    @GameTest(templateNamespace = "mantis", template = "empty")
    public static void generatedResourcesValidateAndFreeze(GameTestHelper helper) {
        StartupData data = new StartupData(); StartupRegistries registry = new StartupRegistries(data);
        try (var context = Mantis.engine().createContext(Map.of(), Map.of())) {
            registry.item("mantis:inspection", context.evaluate("item-properties", "({texture:'minecraft:item/emerald',displayName:'Inspection Item'})"));
            registry.block("mantis:inspection_block", context.evaluate("block-properties", "({displayName:'Inspection Block'})"));
            registry.fluid("mantis:inspection_fluid", context.evaluate("fluid-properties", "({displayName:'Inspection Fluid',tint:'#CCFFFFFF'})"));
            registry.creativeTab("mantis:inspection_tab", context.evaluate("tab-properties", "({displayName:'Inspection Tab',icon:'minecraft:stone',items:['minecraft:stone']})"));
            data.json("mantis:recipes/inspection.json", context.evaluate("data-json", "({type:'minecraft:crafting_special_repairitem'})"));
            data.tag("items", "mantis:inspection", context.evaluate("tag-values", "['minecraft:stone']"));
            data.tag("items", "mantis:inspection", context.evaluate("tag-values-append", "[{id:'missing:optional',required:false}]"));
            boolean rejected = false;
            try { registry.item("mantis:bad", context.evaluate("bad-item", "({maxStackSize:0})")); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Invalid item declaration must fail");
            rejected = false;
            try { registry.item("mantis:inspection_fluid_bucket", context.evaluate("duplicate-bucket", "({})")); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Fluid bucket IDs must be reserved before registration");
            rejected = false;
            try { registry.fluid("mantis:bad_fluid", context.evaluate("bad-fluid", "({tint:'#oops'})")); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Fluid tint must be a valid ARGB color");
            rejected = false;
            try { data.json("mantis:../escape.json", context.evaluate("bad-path", "({})")); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Generated resources must reject parent path segments");
            data.freeze(); registry.freeze();
            try (PackResources server = data.open("test-server", PackType.SERVER_DATA); PackResources assets = data.open("test-assets", PackType.CLIENT_RESOURCES)) {
                helper.assertTrue(server.getMetadataSection(PackMetadataSection.TYPE) != null && assets.getMetadataSection(PackMetadataSection.TYPE) != null, "Generated packs must expose valid pack metadata");
                JsonObject item = read(assets, PackType.CLIENT_RESOURCES, "mantis:models/item/inspection.json");
                helper.assertTrue(item.getAsJsonObject("textures").get("layer0").getAsString().equals("minecraft:item/emerald"), "Generated item model must use the declared texture");
                helper.assertTrue(read(assets, PackType.CLIENT_RESOURCES, "mantis:blockstates/inspection_block.json").getAsJsonObject("variants").has(""), "Generated cube block must have a blockstate");
                helper.assertTrue(read(assets, PackType.CLIENT_RESOURCES, "mantis:lang/en_us.json").get("item.mantis.inspection").getAsString().equals("Inspection Item"), "Generated item names must be preserved alongside block names");
                JsonObject names = read(assets, PackType.CLIENT_RESOURCES, "mantis:lang/en_us.json");
                helper.assertTrue(names.get("fluid.mantis.inspection_fluid").getAsString().equals("Inspection Fluid")
                        && names.get("item.mantis.inspection_fluid_bucket").getAsString().equals("Inspection Fluid Bucket")
                        && names.get("itemGroup.mantis.inspection_tab").getAsString().equals("Inspection Tab"), "Generated fluid, bucket and tab names must coexist");
                helper.assertTrue(read(assets, PackType.CLIENT_RESOURCES, "mantis:models/item/inspection_fluid_bucket.json").getAsJsonObject("textures").get("layer0").getAsString().equals("minecraft:item/water_bucket"), "Fluid bucket must have a generated model");
                helper.assertTrue(read(assets, PackType.CLIENT_RESOURCES, "minecraft:atlases/blocks.json").getAsJsonArray("sources").size() == 2, "Declared fluid sprites must be included in the block atlas");
                helper.assertTrue(read(server, PackType.SERVER_DATA, "mantis:tags/items/inspection.json").getAsJsonArray("values").size() == 2, "Repeated tag declarations must append values");
                helper.assertTrue(server.getResource(PackType.CLIENT_RESOURCES, new ResourceLocation("mantis:models/item/inspection.json")) == null, "Server data pack must not expose client resources");
                List<ResourceLocation> listed = new ArrayList<>(); assets.listResources(PackType.CLIENT_RESOURCES, "mantis", "models/item", (id, value) -> listed.add(id));
                helper.assertTrue(new HashSet<>(listed).equals(Set.of(new ResourceLocation("mantis:models/item/inspection.json"),
                        new ResourceLocation("mantis:models/item/inspection_block.json"), new ResourceLocation("mantis:models/item/inspection_fluid_bucket.json"))),
                        "Pack listing must include item, block-item and fluid-bucket models under the requested path");
            }
            rejected = false;
            try { data.tag("items", "mantis:late", context.evaluate("late", "[]")); }
            catch (IllegalStateException expected) { rejected = true; }
            helper.assertTrue(rejected, "Generated resources must be immutable after startup");
        } catch (IOException error) { throw new IllegalStateException(error); }
        helper.succeed();
    }
    private static JsonObject read(PackResources pack, PackType type, String id) throws IOException {
        try (var input = pack.getResource(type, new ResourceLocation(id)).get(); var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
    }
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
        private CommandDispatcher<CommandSourceStack> oldDispatcher;
        private int stage;

        ReloadCheck(MinecraftServer server, GameTestHelper helper) {
            this.server = server;
            this.helper = helper;
            try { original = Files.readString(ScriptDirectories.SERVER.resolve("smoke.js")); }
            catch (IOException error) { throw new IllegalStateException(error); }
            initialTick = ClockData.get(server).clock().ticks();
            helper.assertTrue(!Boolean.getBoolean("mantis.test.create") || net.minecraftforge.fml.ModList.get().isLoaded("create"), "Requested Create integration must have the real mod installed");
            if (Boolean.getBoolean("mantis.test.firstload")) stage = -2;
        }

        void tick() {
            if (stage == -2) {
                helper.assertTrue(Mantis.hasActive(), "First-load fallback must activate an empty generation");
                helper.assertTrue(server.getRecipeManager().byKey(new ResourceLocation("mantis:smoke")).isEmpty(), "Failed first load must not publish scripted recipes");
                var infusion = (InfusionTestRecipe.Infusion) server.getRecipeManager().byKey(new ResourceLocation("mantis:infusion")).orElseThrow();
                helper.assertTrue(infusion.energy() == 1000 && infusion.json().getAsJsonObject("ritual").get("duration").getAsInt() == 200,
                        "First-load failure must preserve original custom recipes");
                helper.assertTrue(ClockData.get(server).clock().scheduledTasks() == 0 && MantisTestExtension.activeFutures() == 0,
                        "Failed first-load candidate must release timers and futures");
                try { Files.delete(ScriptDirectories.SERVER.resolve("000-initial-failure.js")); }
                catch (IOException error) { throw new IllegalStateException(error); }
                reload = server.reloadResources(server.getPackRepository().getSelectedIds());
                stage = -1;
                helper.assertTrue(false, "Waiting for first-load recovery");
            }
            if (stage == -1) {
                helper.assertTrue(reload.isDone(), "Waiting for first-load recovery");
                helper.assertTrue(!reload.isCompletedExceptionally(), "Fixed first-load scripts must recover through reload");
                stage = 0;
            }
            if (stage == 0) {
                verifyRecipe();
                verifyGameplay();
                verifyCommands();
                oldDispatcher = server.getCommands().getDispatcher();
                helper.assertTrue(ClockData.get(server).clock().scheduledTasks() == 1, "Expected one active script timer");
                helper.assertTrue(ClockData.get(server).clock().remaining("mantis:async") > 0, "Async values must convert and resume on the server thread");
                helper.assertTrue(ClockData.get(server).clock().remaining("mantis:lifecycle") > 0, "Lifecycle start hook must run after clock attachment");
                helper.assertTrue(MantisTestExtension.activeFutures() == 1, "Expected one owned pending future");
                verifyExtensionFailureFallback();
                write(original + "\nevents.on('recipes', () => recipes.custom('mantis:bad', { type: 'mantis:missing_serializer' }));\n");
                reload = server.reloadResources(server.getPackRepository().getSelectedIds());
                stage = 1;
                helper.assertTrue(false, "Waiting for rejected reload");
            }
            if (stage == 1) {
                helper.assertTrue(reload.isDone(), "Waiting for rejected reload");
                helper.assertTrue(reload.isCompletedExceptionally(), "An invalid scripted recipe must reject reload");
                verifyRecipe();
                helper.assertTrue(server.getCommands().getDispatcher() == oldDispatcher && command("mantis:sum 2") == 2, "Invalid recipe reload must retain the old command dispatcher");
                helper.assertTrue(ClockData.get(server).clock().scheduledTasks() == 1, "Failed reload must keep the previous timer");
                helper.assertTrue(MantisTestExtension.activeFutures() == 1, "Failed reload must cancel the candidate future and retain the old one");
                write(original + "\ncommands.register('give', {permission:2}, () => 1);\n");
                reload = server.reloadResources(server.getPackRepository().getSelectedIds());
                stage = 2;
                helper.assertTrue(false, "Waiting for command collision reload");
            }
            if (stage == 2) {
                helper.assertTrue(reload.isDone(), "Waiting for command collision reload");
                helper.assertTrue(reload.isCompletedExceptionally(), "Script commands must not overwrite existing roots");
                helper.assertTrue(server.getCommands().getDispatcher() == oldDispatcher && command("mantis:sum 2") == 2, "Command collision must retain previous commands");
                helper.assertTrue(ClockData.get(server).clock().scheduledTasks() == 1 && MantisTestExtension.activeFutures() == 1, "Rejected command generation must release its owned work");
                write(original.replace("commands.register('mantis:obsolete', {permission:2}, () => 3);", ""));
                reload = server.reloadResources(server.getPackRepository().getSelectedIds()); stage = 3;
                helper.assertTrue(false, "Waiting for successful reload");
            }
            if (stage == 3) {
                helper.assertTrue(reload.isDone(), "Waiting for successful reload");
                helper.assertTrue(!reload.isCompletedExceptionally(), "Corrected scripts must reload successfully");
                verifyRecipe();
                var clock = ClockData.get(server).clock();
                verifyGameplay();
                verifyCommands();
                helper.assertTrue(server.getCommands().getDispatcher().getRoot().getChild("mantis:obsolete") == null, "Removed commands must disappear from the new dispatcher");
                helper.assertTrue(!oldDispatcher.getRoot().getChild("mantis:sum").canUse(server.createCommandSourceStack()), "Old command nodes must release their script callbacks");
                helper.assertTrue(clock.ticks() > initialTick, "Internal clock must keep advancing through reloads");
                helper.assertTrue(clock.scheduledTasks() == 1, "Successful reload must replace the old timer");
                helper.assertTrue(clock.remaining("mantis:smoke") > 0, "Reload must preserve named cooldowns");
                helper.assertTrue(MantisTestExtension.activeFutures() == 1 && MantisTestExtension.cancelledFutures() >= 2, "Successful reload must cancel old async work and replace it once");
                CompoundTag saved = ClockData.get(server).save(new CompoundTag());
                helper.assertTrue(saved.getLong("ticks") == clock.ticks(), "World data must save the internal counter");
                helper.assertTrue(saved.getCompound("cooldowns").getLong("mantis:smoke") > clock.ticks(), "World data must save cooldown deadlines");
                stage = 4;
            }
        }

        private int command(String text) {
            try { return server.getCommands().getDispatcher().execute(text, server.createCommandSourceStack()); }
            catch (CommandSyntaxException error) { throw new IllegalStateException(error); }
        }
        private void verifyCommands() {
            var dispatcher = server.getCommands().getDispatcher(); var source = server.createCommandSourceStack();
            helper.assertTrue(command("mantis:sum 2 3.5") == 6 && command("mantis:sum 2") == 2, "Required and optional numeric command arguments must reach JS");
            helper.assertTrue(command("mantis:flag true") == 7 && command("mantis:flag false") == 8 && command("mantis:words stone hello world") == 9, "Boolean, word and greedy command arguments must parse");
            int marks = MantisTestExtension.marks("command");
            helper.assertTrue(command("mantis:echo") == 1 && command("mantis:echo \"hello world\"") == 1 && MantisTestExtension.marks("command") == marks + 2, "Optional strings and undefined return must execute");
            try {
                helper.assertTrue(dispatcher.execute("mantis:echo first", source.withPermission(0)) == 1, "Public command must allow permission zero");
                boolean rejected = false;
                try { dispatcher.execute("mantis:sum 2", source.withPermission(0)); } catch (CommandSyntaxException expected) { rejected = true; }
                helper.assertTrue(rejected, "Operator commands must reject permission zero");
                rejected = false;
                try { dispatcher.execute("mantis:sum 99", source); } catch (CommandSyntaxException expected) { rejected = true; }
                helper.assertTrue(rejected, "Argument bounds must be enforced before executing JS");
                var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("mantis:echo f", source)).join();
                helper.assertTrue(suggestions.getList().stream().anyMatch(suggestion -> suggestion.getText().equals("first")), "Static command suggestions must reach Brigadier");
            } catch (CommandSyntaxException error) { throw new IllegalStateException(error); }
            for (int i = 0; i < 10; i++) helper.assertTrue(command("mantis:bad_return") == 0, "Invalid command returns must report failure");
            helper.assertTrue(!dispatcher.getRoot().getChild("mantis:bad_return").canUse(source), "Repeated command failures must disable and release their callback");
        }

        private void verifyExtensionFailureFallback() {
            Map<ResourceLocation, JsonElement> jsons = new HashMap<>();
            ResourceLocation id = new ResourceLocation("mantis:untouched");
            JsonObject originalJson = JsonParser.parseString("{\"type\":\"minecraft:crafting_special_repairitem\"}").getAsJsonObject();
            jsons.put(id, originalJson);
            int before = MantisTestExtension.registrationAttempts;
            MantisTestExtension.failRegistration = true;
            try {
                try (PreparedScripts fallback = RecipeReload.prepare(jsons, ICondition.IContext.EMPTY, true)) {
                    helper.assertTrue(fallback.session().scriptCount() == 0, "Broken extension must produce an empty first-load fallback");
                    helper.assertTrue(MantisTestExtension.registrationAttempts == before + 1, "Fallback must not retry the broken extension");
                    helper.assertTrue(jsons.size() == 1 && jsons.get(id) == originalJson, "Fallback must leave recipe JSON unchanged");
                }
                boolean rejected = false;
                try { RecipeReload.prepare(jsons, ICondition.IContext.EMPTY, false); }
                catch (IllegalStateException expected) { rejected = true; }
                helper.assertTrue(rejected, "Strict reload must reject extension failures");
            } finally {
                MantisTestExtension.failRegistration = false;
                Mantis.discardPending();
            }
        }

        private void verifyRecipe() {
            verifyContent();
            var recipe = server.getRecipeManager().byKey(new ResourceLocation("mantis:smoke"));
            helper.assertTrue(recipe.isPresent(), "Scripted recipe must load through RecipeManager");
            var result = recipe.orElseThrow().getResultItem(server.registryAccess());
            helper.assertTrue(result.is(Items.STICK) && result.getCount() == 4, "Nested recipe patch must reach the installed serializer");
            var custom = server.getRecipeManager().byKey(new ResourceLocation("mantis:infusion")).orElseThrow();
            helper.assertTrue(custom instanceof InfusionTestRecipe.Infusion, "Non-crafting recipe must retain its registered serializer and type");
            var infusion = (InfusionTestRecipe.Infusion) custom;
            helper.assertTrue(infusion.energy() == 32000, "Generic patches must change nested machine/fusion energy");
            helper.assertTrue(infusion.getResultItem(server.registryAccess()).is(Items.DIAMOND) && infusion.getResultItem(server.registryAccess()).getCount() == 2,
                    "Generic array paths must change a custom output schema");
            helper.assertTrue(infusion.json().getAsJsonObject("ritual").get("duration").getAsInt() == 400
                    && infusion.json().getAsJsonObject("ritual").getAsJsonObject("extra").get("preserved").getAsBoolean(),
                    "Generic patching must change ritual fields and preserve opaque metadata through reloads");
            verifyCreate();
        }

        private Object inspect(Object object, String method) {
            try { return object.getClass().getMethod(method).invoke(object); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect installed Create API: " + method, error); }
        }
        private void verifyCreate() {
            if (!net.minecraftforge.fml.ModList.get().isLoaded("create")) return;
            var recipes = server.getRecipeManager();
            var alloy = recipes.byKey(new ResourceLocation("create:mixing/andesite_alloy")).orElseThrow();
            helper.assertTrue(alloy.getIngredients().get(0).test(new ItemStack(Items.STONE))
                    && alloy.getResultItem(server.registryAccess()).is(Items.DIAMOND) && alloy.getResultItem(server.registryAccess()).getCount() == 2,
                    "Existing Create mixing recipe must accept replaced input and output");
            var crushing = recipes.byKey(new ResourceLocation("create:crushing/iron_ore")).orElseThrow();
            var outputs = (List<?>) inspect(crushing, "getRollableResults");
            helper.assertTrue(inspect(crushing, "getProcessingDuration").equals(42)
                    && ((ItemStack) inspect(outputs.get(2), "getStack")).is(Items.EMERALD)
                    && ((Number) inspect(outputs.get(2), "getChance")).floatValue() == 0.75f, "Create duration and probabilistic outputs must preserve unedited chances");
            helper.assertTrue(recipes.byKey(new ResourceLocation("create:compacting/blaze_cake")).isEmpty(), "Existing Create recipes must be removable");
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("mantis:script_item"));
            for (String type : List.of("mixing", "crushing", "milling", "pressing", "compacting", "filling", "emptying")) {
                var recipe = recipes.byKey(new ResourceLocation("mantis:create_" + type)).orElseThrow();
                helper.assertTrue(new ResourceLocation("create:" + type).equals(ForgeRegistries.RECIPE_SERIALIZERS.getKey(recipe.getSerializer())), "Recipe must use the installed Create serializer: " + type);
                if (!type.equals("filling") && !type.equals("emptying")) helper.assertTrue(recipe.getResultItem(server.registryAccess()).is(item)
                        && recipe.getResultItem(server.registryAccess()).getCount() == 2 && inspect(recipe, "getProcessingDuration").equals(40), "Scripted processing result and time must reach Create: " + type);
            }
            var filling = recipes.byKey(new ResourceLocation("mantis:create_filling")).orElseThrow();
            Object input = ((List<?>) inspect(filling, "getFluidIngredients")).get(0);
            helper.assertTrue(inspect(input, "getRequiredAmount").equals(500), "Create fluid ingredient amount must accept generic JSON edits");
            var fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation("mantis:script_sap"));
            try { helper.assertTrue((boolean) input.getClass().getMethod("test", net.minecraftforge.fluids.FluidStack.class).invoke(input, new net.minecraftforge.fluids.FluidStack(fluid, 500)), "Create must accept the fluid registered by Mantis"); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            var emptying = recipes.byKey(new ResourceLocation("mantis:create_emptying")).orElseThrow();
            var result = (net.minecraftforge.fluids.FluidStack) ((List<?>) inspect(emptying, "getFluidResults")).get(0);
            helper.assertTrue(result.getFluid() == fluid && result.getAmount() == 250, "Create fluid output must resolve the Mantis fluid registry entry");
            helper.assertTrue(inspect(recipes.byKey(new ResourceLocation("mantis:create_mixing")).orElseThrow(), "getRequiredHeat").toString().equals("HEATED"), "Create mixing must retain its heat requirement");
            Mantis.log("Verified installed Create recipes");
        }

        private void verifyContent() {
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("mantis:script_item"));
            helper.assertTrue(item != null && item.getMaxStackSize() == 16 && item.isFireResistant(), "Startup item properties must reach the Forge registry");
            var food = ForgeRegistries.ITEMS.getValue(new ResourceLocation("mantis:script_food"));
            helper.assertTrue(food != null && food.getFoodProperties() != null && food.getFoodProperties().getNutrition() == 3, "Startup food properties must register");
            var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("mantis:script_block"));
            helper.assertTrue(block != null && block.defaultBlockState().getLightEmission() == 7
                    && ForgeRegistries.ITEMS.containsKey(new ResourceLocation("mantis:script_block")), "Startup block and its block item must register");
            helper.assertTrue(item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, new ResourceLocation("mantis:script_inputs"))), "Generated item tags must bind to the new item");
            helper.assertTrue(block.builtInRegistryHolder().is(TagKey.create(Registries.BLOCK, new ResourceLocation("mantis:script_blocks"))), "Generated block tags must bind to the new block");
            var fluid = (net.minecraftforge.fluids.ForgeFlowingFluid) ForgeRegistries.FLUIDS.getValue(new ResourceLocation("mantis:script_sap"));
            var flowing = ForgeRegistries.FLUIDS.getValue(new ResourceLocation("mantis:flowing_script_sap"));
            var bucket = ForgeRegistries.ITEMS.getValue(new ResourceLocation("mantis:script_sap_bucket"));
            helper.assertTrue(fluid != null && fluid.getSource() == fluid && fluid.getFlowing() == flowing && fluid.getBucket() == bucket
                    && fluid.defaultFluidState().isSource() && !flowing.defaultFluidState().isSource(), "Fluid family must resolve its source, flowing fluid and bucket");
            helper.assertTrue(fluid.getFluidType().getDensity() == 1200 && fluid.getFluidType().getViscosity() == 1500
                    && fluid.getFluidType().getTemperature() == 310 && fluid.getTickDelay(helper.getLevel()) == 8, "Fluid properties must reach Forge");
            helper.assertTrue(fluid.builtInRegistryHolder().is(TagKey.create(Registries.FLUID, new ResourceLocation("mantis:script_sap")))
                    && flowing.builtInRegistryHolder().is(TagKey.create(Registries.FLUID, new ResourceLocation("mantis:script_sap"))), "Generated fluid tags must bind both forms");
            BlockPos fluidPos = helper.absolutePos(new BlockPos(4, 1, 4));
            helper.getLevel().setBlockAndUpdate(fluidPos, fluid.defaultFluidState().createLegacyBlock());
            helper.assertTrue(helper.getLevel().getFluidState(fluidPos).getType() == fluid && bucket.getMaxStackSize() == 1
                    && bucket.getCraftingRemainingItem(new ItemStack(bucket)).is(Items.BUCKET), "Fluid must place in a world and retain its empty bucket remainder");
            var liquidBlock = (net.minecraft.world.level.block.LiquidBlock) ForgeRegistries.BLOCKS.getValue(new ResourceLocation("mantis:script_sap"));
            helper.assertTrue(liquidBlock.pickupBlock(helper.getLevel(), fluidPos, helper.getLevel().getBlockState(fluidPos)).is(bucket), "Placed fluid must be collectable in its own bucket");
            var tab = BuiltInRegistries.CREATIVE_MODE_TAB.get(new ResourceLocation("mantis:script_tab"));
            tab.buildContents(new CreativeModeTab.ItemDisplayParameters(FeatureFlags.DEFAULT_FLAGS, true, server.registryAccess()));
            helper.assertTrue(tab.getIconItem().is(bucket) && tab.getDisplayItems().stream().anyMatch(stack -> stack.is(item))
                    && tab.getDisplayItems().stream().anyMatch(stack -> stack.is(bucket)), "Scripted creative tab must resolve its icon and content after item registration");
            var ingredients = BuiltInRegistries.CREATIVE_MODE_TAB.get(new ResourceLocation("minecraft:ingredients"));
            ingredients.buildContents(new CreativeModeTab.ItemDisplayParameters(FeatureFlags.DEFAULT_FLAGS, true, server.registryAccess()));
            helper.assertTrue(ingredients.getDisplayItems().stream().anyMatch(stack -> stack.is(bucket)), "Existing creative tabs must accept scripted items");
            var drops = server.getLootData().getLootTable(new ResourceLocation("mantis:chests/script_reward")).getRandomItems(
                    new LootParams.Builder(helper.getLevel()).withParameter(LootContextParams.ORIGIN, Vec3.ZERO).create(LootContextParamSets.CHEST));
            helper.assertTrue(drops.size() == 1 && drops.get(0).is(item) && drops.get(0).getCount() == 2, "Generated loot table must produce the scripted item");
            for (String kind : List.of("shaped", "shapeless", "smelting", "blasting", "smoking", "campfire", "cut", "smith", "infusion"))
                helper.assertTrue(server.getRecipeManager().byKey(new ResourceLocation("mantis:built_" + kind)).isPresent(), "Recipe builder must load: " + kind);
            var shaped = server.getRecipeManager().byKey(new ResourceLocation("mantis:built_shaped")).orElseThrow().getResultItem(server.registryAccess());
            helper.assertTrue(shaped.is(item) && shaped.getCount() == 2, "Vanilla builder must use newly registered content");
            var shapeless = server.getRecipeManager().byKey(new ResourceLocation("mantis:built_shapeless")).orElseThrow();
            helper.assertTrue(shapeless.getIngredients().get(0).test(new ItemStack(Items.GRAVEL)), "Fresh generated tag must match replacement during recipe loading");
            var infusion = (InfusionTestRecipe.Infusion) server.getRecipeManager().byKey(new ResourceLocation("mantis:built_infusion")).orElseThrow();
            helper.assertTrue(infusion.energy() == 500 && infusion.getResultItem(server.registryAccess()).is(Items.DIAMOND)
                    && infusion.getResultItem(server.registryAccess()).getCount() == 4 && infusion.getIngredients().get(0).test(new ItemStack(Items.IRON_INGOT)),
                    "Schema builder and semantic replacements must reach a custom non-crafting serializer");
        }

        private void verifyGameplay() {
            var level = helper.getLevel();
            var zombie = EntityType.ZOMBIE.create(level);
            var cow = EntityType.COW.create(level);
            int hurtCount = MantisTestExtension.marks("hurt");
            LivingHurtEvent skipped = new LivingHurtEvent(cow, level.damageSources().generic(), 5);
            MinecraftForge.EVENT_BUS.post(skipped);
            helper.assertTrue(skipped.getAmount() == 5 && MantisTestExtension.marks("hurt") == hurtCount, "Entity filter must skip other entity types");
            LivingHurtEvent changed = new LivingHurtEvent(zombie, level.damageSources().generic(), 5);
            MinecraftForge.EVENT_BUS.post(changed);
            helper.assertTrue(changed.getAmount() == 2 && MantisTestExtension.marks("hurt") == hurtCount + 1
                    && zombie.getPersistentData().getCompound("mantis").getString("hurt").equals("yes"), "Script must change damage and persistent entity data synchronously");
            var player = FakePlayerFactory.getMinecraft(level);
            BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
            player.setPos(pos.getX(), pos.getY(), pos.getZ());
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            int before = MantisTestExtension.marks("block");
            BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player);
            MinecraftForge.EVENT_BUS.post(event);
            helper.assertTrue(event.isCanceled() && MantisTestExtension.marks("block") == before + 1
                    && player.getPersistentData().getCompound("mantis").getString("block").equals("yes"), "Script must cancel a matching block event and use player helpers");
            helper.assertTrue(player.getInventory().countItem(ForgeRegistries.ITEMS.getValue(new ResourceLocation("mantis:script_item"))) >= 3, "Player give must reach the inventory");
            var replacement = new net.minecraftforge.common.util.FakePlayer(level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "MantisClone"));
            MinecraftForge.EVENT_BUS.post(new PlayerEvent.Clone(replacement, player, true));
            helper.assertTrue(replacement.getPersistentData().getCompound("mantis").getString("block").equals("yes"), "Mantis player data must survive player cloning after death");
            BlockEvent.BreakEvent second = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player);
            MinecraftForge.EVENT_BUS.post(second);
            helper.assertTrue(!second.isCanceled() && MantisTestExtension.marks("block") == before + 1, "Filtered once listener must unsubscribe after its first matching event");
            int crafted = MantisTestExtension.marks("craft");
            MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player, new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("mantis:script_item")), 2), player.getInventory()));
            helper.assertTrue(MantisTestExtension.marks("craft") == crafted + 1, "Crafted event must export item stacks correctly");
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }

        private void write(String text) {
            try { Files.writeString(ScriptDirectories.SERVER.resolve("smoke.js"), text); }
            catch (IOException error) { throw new IllegalStateException(error); }
        }
    }
}
