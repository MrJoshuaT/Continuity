package me.pepperbell.continuity.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.pepperbell.continuity.client.resource.CustomBlockLayers;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;

@Mixin(RenderLayers.class)
public class RenderLayersMixin {
	@Inject(at = @At("HEAD"), method = "getBlockLayer(Lnet/minecraft/block/BlockState;)Lnet/minecraft/client/render/RenderLayer;", cancellable = true)
	private static void onHeadGetBlockLayer(BlockState state, CallbackInfoReturnable<RenderLayer> cir) {
		RenderLayer layer = CustomBlockLayers.getLayer(state);
		if (layer != null) {
			cir.setReturnValue(layer);
		}
	}
}
