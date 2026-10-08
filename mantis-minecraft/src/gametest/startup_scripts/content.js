import { registries } from 'minecraft:registries';
import { schemas } from 'minecraft:schemas';
import { data } from 'minecraft:data';

registries.item('mantis:script_item', { maxStackSize: 16, fireResistant: true, texture: 'minecraft:item/emerald', displayName: 'Script Item' });
registries.item('mantis:script_food', { food: { nutrition: 3, saturation: 0.5, alwaysEat: true } });
registries.block('mantis:script_block', { hardness: 2, resistance: 5, light: 7 });
const sap = registries.fluid('mantis:script_sap', { displayName: 'Script Sap', density: 1200, viscosity: 1500, temperature: 310,
  light: 4, tickRate: 8, resistance: 12, tint: '#CC88BB44', canConvertToSource: true });
data.tag('fluids', 'mantis:script_sap', [sap.source, sap.flowing]);
registries.creativeTab('mantis:script_tab', {displayName:'Script Tab', icon:sap.bucket,
  items:['mantis:script_item','mantis:script_block',sap.bucket], after:['minecraft:ingredients']});
registries.tabItems('minecraft:ingredients', ['mantis:script_item', sap.bucket]);
data.tag('items', 'mantis:script_inputs', ['mantis:script_item', 'minecraft:stone']);
data.tag('items', 'mantis:script_inputs', [{ id: 'missing:optional', required: false }]);
data.tag('blocks', 'mantis:script_blocks', ['mantis:script_block']);
data.lootTable('mantis:chests/script_reward', {
  type: 'minecraft:chest',
  pools: [{ rolls: 1, entries: [{ type: 'minecraft:item', name: 'mantis:script_item',
    functions: [{ function: 'minecraft:set_count', count: 2 }] }] }]
});
schemas.register('mantis:test_infusion', {
  template: { fusion: { tier: 'DRACONIC' }, ritual: { id: 'mantis:summon', extra: { preserved: true } } },
  fields: {
    inputs: { path: '/catalysts', kind: 'ingredients', role: 'input' },
    outputs: { path: '/outputs', kind: 'items', role: 'output' },
    energy: { path: '/fusion/energy', kind: 'positive_int' },
    duration: { path: '/ritual/duration', kind: 'positive_int', default: 200 }
  }
});
