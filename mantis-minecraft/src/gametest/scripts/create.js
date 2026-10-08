import { mods } from 'minecraft:mods';
import { events } from 'mantis:events';
import { recipes } from 'minecraft:recipes';

if (mods.isLoaded('create')) events.on('recipes', () => {
  if (!recipes.contains({id:'create:mixing/andesite_alloy'})) throw new Error('Create recipe resources were not loaded');
  recipes.replaceInput({id:'create:mixing/andesite_alloy'}, 'minecraft:andesite', 'minecraft:stone');
  recipes.replaceOutput({id:'create:mixing/andesite_alloy'}, 'create:andesite_alloy', recipes.item('minecraft:diamond', 2));
  recipes.set({id:'create:crushing/iron_ore'}, '/processingTime', 42);
  recipes.replaceOutput({id:'create:crushing/iron_ore'}, 'create:experience_nugget', 'minecraft:emerald');
  recipes.remove({id:'create:compacting/blaze_cake'});
  for (const type of ['mixing', 'crushing', 'milling', 'pressing', 'compacting']) {
    recipes.custom('mantis:create_' + type, {type:'create:' + type, ingredients:[{item:'minecraft:stone'}],
      results:[{item:'mantis:script_item',count:2}], processingTime:40,
      ...(type === 'mixing' ? {heatRequirement:'heated'} : {})});
  }
  recipes.custom('mantis:create_filling', {type:'create:filling',
    ingredients:[{item:'minecraft:glass_bottle'}, recipes.fluid('mantis:script_sap', 250)],
    results:[{item:'minecraft:honey_bottle'}]});
  recipes.set({id:'mantis:create_filling'}, '/ingredients/1/amount', 500);
  recipes.custom('mantis:create_emptying', {type:'create:emptying', ingredients:[{item:'minecraft:honey_bottle'}],
    results:[{item:'minecraft:glass_bottle'}, recipes.fluid('mantis:script_sap', 250)]});
});
