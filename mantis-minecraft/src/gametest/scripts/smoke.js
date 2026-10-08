import { events } from 'mantis:events';
import { clock } from 'mantis:clock';
import { recipes } from 'minecraft:recipes';
import { lifecycle } from 'mantis:lifecycle';
import { test } from 'mantis_test:async';
import { server } from 'minecraft:server';
import { commands } from 'minecraft:commands';

commands.register('mantis:sum', { permission: 2, arguments: [
  { name: 'base', type: 'integer', min: 1, max: 10 },
  { name: 'extra', type: 'double', min: 0, max: 10, optional: true }
] }, event => {
  if (!test.onServerThread() || event.source.player() !== null || event.source.dimension() !== 'minecraft:overworld'
      || !event.source.hasPermission(2) || event.source.name().length === 0) throw new Error('Command source binding failed');
  return event.args.base + Math.round(event.args.extra ?? 0);
});
commands.register('mantis:echo', { permission: 0, arguments: [
  { name: 'message', type: 'string', optional: true, suggestions: ['first', 'second'] }
] }, event => { event.source.reply(event.args.message ?? 'empty'); test.mark('command'); });
commands.register('mantis:flag', { permission: 2, arguments: [{ name: 'enabled', type: 'boolean' }] }, event => event.args.enabled ? 7 : 8);
commands.register('mantis:words', { permission: 2, arguments: [{name:'item',type:'word'}, {name:'text',type:'greedy'}] }, event => {
  if (event.args.item !== 'stone' || event.args.text !== 'hello world') throw new Error('Command words lost their value');
  return 9;
});
commands.register('mantis:bad_return', { permission: 2 }, () => 'bad');
commands.register('mantis:obsolete', {permission:2}, () => 3);

test.hold();
test.load().then(data => {
  if (!test.onServerThread() || data.id !== 'mantis:async' || data.component.text !== 'async'
      || !Array.isArray(data.values) || data.values.reduce((a, b) => a + b, 0) !== 42) {
    throw new Error('Async host conversion or server scheduling failed');
  }
  clock.cooldown('mantis:async', 1000000);
});
lifecycle.on('start', () => {
  if (lifecycle.state() !== 'running' || !test.onServerThread()) throw new Error('Wrong lifecycle thread or state');
  clock.cooldown('mantis:lifecycle', 1000000);
});

clock.every(1, () => {});
events.once('server.started', () => clock.cooldown('mantis:smoke', 1000000));
events.once('server.reloaded', () => {
  if (clock.remaining('mantis:smoke') === 0) clock.cooldown('mantis:smoke', 1000000);
});

events.on('recipes', () => {
  recipes.shaped('mantis:built_shaped', recipes.item('mantis:script_item', 2), ['AA'], { A: 'stone' });
  recipes.shapeless('mantis:built_shapeless', 'stick', ['stone']);
  if (!recipes.contains({ id: 'mantis:built_shapeless', input: '#mantis:script_inputs' })) {
    throw new Error('Recipe filters must use tags from the current reload');
  }
  recipes.replaceInput({ id: 'mantis:built_shapeless' }, '#mantis:script_inputs', 'gravel');
  for (const kind of ['smelting', 'blasting', 'smoking', 'campfire']) {
    recipes[kind]('mantis:built_' + kind, 'iron_ingot', 'raw_iron').experience(0.5).cookingTime(40);
  }
  recipes.stonecutting('mantis:built_cut', recipes.item('stone_slab', 2), 'stone');
  recipes.smithing('mantis:built_smith', 'netherite_sword', 'netherite_upgrade_smithing_template', 'diamond_sword', 'netherite_ingot');
  recipes.create('mantis:built_infusion', 'mantis:test_infusion', {
    inputs: ['mantis:script_item'], outputs: [recipes.item('emerald', 4)], energy: 500, duration: 10
  });
  recipes.replaceInput({ id: 'mantis:built_infusion' }, '#mantis:script_inputs', 'iron_ingot');
  recipes.replaceOutput({ id: 'mantis:built_infusion' }, 'emerald', 'diamond');
  recipes.set({ type: 'mantis:test_infusion', not: { id: 'mantis:built_infusion' } }, '/fusion/energy', 32000);
  recipes.set({ id: 'mantis:infusion' }, '/outputs/0/count', 2);
  recipes.patch({ type: 'mantis:test_infusion', not: { id: 'mantis:built_infusion' } }, json => {
    json.ritual.duration = 400;
    return json;
  });
  recipes.custom('mantis:smoke', {
    type: 'minecraft:crafting_shapeless',
    ingredients: [{ item: 'minecraft:stone' }],
    result: { item: 'minecraft:stick', count: 2 }
  });
  recipes.patch({ id: 'mantis:smoke' }, json => {
    json.result.count = 4;
    return json;
  });
});

events.on('entity.hurt', { entityType: 'minecraft:zombie', dimension: 'minecraft:overworld' }, event => {
  event.control.damage(2);
  event.entity.data('hurt', 'yes');
  const position = event.entity.position();
  if (!Number.isFinite(position.x) || event.entity.health() <= 0 || !Array.isArray(server.players())
      || server.level(event.dimension).dimension() !== event.dimension) throw new Error('Entity/server binding conversion failed');
  test.mark('hurt');
});
events.once('block.broken', { block: 'minecraft:stone' }, event => {
  event.control.cancel();
  if (!event.control.cancelled() || event.position.y !== event.player.position().y
      || event.level.block(event.position.x, event.position.y, event.position.z) !== 'minecraft:stone') {
    throw new Error('Block event position/player/level bindings failed');
  }
  event.player.data('block', 'yes');
  event.player.give('mantis:script_item', 3);
  test.mark('block');
});
events.on('item.crafted', { item: 'mantis:script_item' }, event => {
  if (event.stack.id() !== 'mantis:script_item' || event.stack.count() !== 2) throw new Error('Stack binding failed');
  test.mark('craft');
});
