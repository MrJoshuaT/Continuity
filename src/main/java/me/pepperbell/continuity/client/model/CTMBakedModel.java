package me.pepperbell.continuity.client.model;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.pepperbell.continuity.api.client.ProcessingDataKey;
import me.pepperbell.continuity.api.client.ProcessingDataKeyRegistry;
import me.pepperbell.continuity.api.client.QuadProcessor;
import me.pepperbell.continuity.client.config.ContinuityConfig;
import me.pepperbell.continuity.client.util.RenderUtil;
import me.pepperbell.continuity.impl.client.ProcessingDataKeyRegistryImpl;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;

public class CTMBakedModel extends ForwardingBakedModel {
	public static final int MULTIPASS_LIMIT = 3;

	protected static final ThreadLocal<ObjectContainer> CONTAINERS = ThreadLocal.withInitial(ObjectContainer::new);

	protected final List<QuadProcessor> processors;
	@Nullable
	protected final List<QuadProcessor> multipassProcessors;

	public CTMBakedModel(BakedModel wrapped, List<QuadProcessor> processors, @Nullable List<QuadProcessor> multipassProcessors) {
		this.wrapped = wrapped;
		this.processors = processors;
		this.multipassProcessors = multipassProcessors;
	}

	@Override
	public void emitBlockQuads(BlockRenderView blockView, BlockState state, BlockPos pos, Supplier<Random> randomSupplier, RenderContext context) {
		if (ContinuityConfig.INSTANCE.disableCTM.get()) {
			super.emitBlockQuads(blockView, state, pos, randomSupplier, context);
			return;
		}
		ObjectContainer container = CONTAINERS.get();
		if (container.ctmDisabled) {
			super.emitBlockQuads(blockView, state, pos, randomSupplier, context);
			return;
		}

		CTMQuadTransform quadTransform = container.quadTransform;
		quadTransform.prepare(processors, multipassProcessors, state, blockView, pos, randomSupplier, ContinuityConfig.INSTANCE.useManualCulling.get());

		context.pushTransform(quadTransform);
		super.emitBlockQuads(blockView, state, pos, randomSupplier, context);
		context.popTransform();

		quadTransform.processingContext.accept(context);
		quadTransform.reset();
	}

	@Override
	public boolean isVanillaAdapter() {
		return false;
	}

	public static boolean isCTMDisabled() {
		return CONTAINERS.get().ctmDisabled;
	}

	public static void setCTMDisabled(boolean disabled) {
		CONTAINERS.get().ctmDisabled = disabled;
	}

	protected static class ObjectContainer {
		public boolean ctmDisabled = false;
		public CTMQuadTransform quadTransform = new CTMQuadTransform();
	}

	protected static class CTMQuadTransform implements RenderContext.QuadTransform {
		protected final ManualCullingCache cullingCache = new ManualCullingCache();
		protected final ProcessingContextImpl processingContext = new ProcessingContextImpl();

		protected List<QuadProcessor> processors;
		protected List<QuadProcessor> multipassProcessors;
		protected BlockState state;
		protected BlockRenderView blockView;
		protected BlockPos pos;
		protected Supplier<Random> randomSupplier;

		protected boolean useManualCulling;
		protected SpriteFinder spriteFinder;

		@Override
		public boolean transform(MutableQuadView quad) {
			if (useManualCulling) {
				Direction cullFace = quad.cullFace();
				if (cullFace != null) {
					if (cullingCache.shouldCull(state, blockView, pos, cullFace)) {
						return false;
					}
				}
			}

			Boolean result = transformOnce(quad, processors, 0);
			if (result != null) {
				return result;
			}
			if (multipassProcessors != null) {
				for (int pass = 0; pass < MULTIPASS_LIMIT; pass++) {
					result = transformOnce(quad, multipassProcessors, pass + 1);
					if (result != null) {
						return result;
					}
				}
			}

			return true;
		}

		protected Boolean transformOnce(MutableQuadView quad, List<QuadProcessor> processors, int pass) {
			Sprite sprite = spriteFinder.find(quad, 0);
			int amount = processors.size();
			for (int i = 0; i < amount; i++) {
				QuadProcessor processor = processors.get(i);
				QuadProcessor.ProcessingResult result = processor.processQuad(quad, sprite, blockView, state, pos, randomSupplier, pass, i, processingContext);
				if (result == QuadProcessor.ProcessingResult.CONTINUE) {
					continue;
				}
				if (result == QuadProcessor.ProcessingResult.STOP) {
					return null;
				}
				if (result == QuadProcessor.ProcessingResult.ABORT_AND_CANCEL_QUAD) {
					return false;
				}
				if (result == QuadProcessor.ProcessingResult.ABORT_AND_RENDER_QUAD) {
					return true;
				}
			}
			return true;
		}

		public void prepare(List<QuadProcessor> processors, List<QuadProcessor> multipassProcessors, BlockState state, BlockRenderView blockView, BlockPos pos, Supplier<Random> randomSupplier, boolean useManualCulling) {
			this.processors = processors;
			this.multipassProcessors = multipassProcessors;
			this.state = state;
			this.blockView = blockView;
			this.pos = pos;
			this.randomSupplier = randomSupplier;
			this.useManualCulling = useManualCulling;
			spriteFinder = RenderUtil.getSpriteFinder();
		}

		public void reset() {
			processors = null;
			multipassProcessors = null;
			state = null;
			blockView = null;
			pos = null;
			randomSupplier = null;
			useManualCulling = false;
			spriteFinder = null;

			cullingCache.reset();
			processingContext.reset();
		}
	}

	protected static class ManualCullingCache {
		protected static final Boolean[] EMPTY_CULL_CACHE = new Boolean[Direction.values().length];

		protected Boolean[] cullCache = new Boolean[Direction.values().length];
		protected BlockPos.Mutable mutablePos = new BlockPos.Mutable();

		public boolean shouldCull(BlockState state, BlockRenderView blockView, BlockPos pos, Direction cullFace) {
			int ordinal = cullFace.ordinal();
			Boolean cull = cullCache[ordinal];
			if (cull == null) {
				cull = !Block.shouldDrawSide(state, blockView, pos, cullFace, mutablePos.set(pos, cullFace));
				cullCache[ordinal] = cull;
			}
			return cull;
		}

		public void reset() {
			System.arraycopy(EMPTY_CULL_CACHE, 0, cullCache, 0, EMPTY_CULL_CACHE.length);
		}
	}

	protected static class ProcessingContextImpl implements QuadProcessor.ProcessingContext {
		protected List<Consumer<QuadEmitter>> emitterConsumers = new ObjectArrayList<>();
		protected List<Mesh> meshes = new ObjectArrayList<>();
		protected Object[] processingData = new Object[ProcessingDataKeyRegistry.INSTANCE.getRegisteredAmount()];

		@Override
		public void addEmitterConsumer(Consumer<QuadEmitter> consumer) {
			emitterConsumers.add(consumer);
		}

		@Override
		public void addMesh(Mesh mesh) {
			meshes.add(mesh);
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T getData(ProcessingDataKey<T> key) {
			int index = key.getRawId();
			T data = (T) processingData[index];
			if (data == null) {
				data = key.getValueSupplier().get();
				processingData[index] = data;
			}
			return data;
		}

		@SuppressWarnings("unchecked")
		public <T> T getDataOrNull(ProcessingDataKey<T> key) {
			return (T) processingData[key.getRawId()];
		}

		public void accept(RenderContext context) {
			if (!emitterConsumers.isEmpty()) {
				QuadEmitter quadEmitter = context.getEmitter();
				int amount = emitterConsumers.size();
				for (int i = 0; i < amount; i++) {
					emitterConsumers.get(i).accept(quadEmitter);
				}
			}
			if (!meshes.isEmpty()) {
				Consumer<Mesh> meshConsumer = context.meshConsumer();
				int amount = meshes.size();
				for (int i = 0; i < amount; i++) {
					meshConsumer.accept(meshes.get(i));
				}
			}
		}

		protected void resetData() {
			List<ProcessingDataKey<?>> allResetable = ProcessingDataKeyRegistryImpl.INSTANCE.getAllResetable();
			int amount = allResetable.size();
			for (int i = 0; i < amount; i++) {
				resetData(allResetable.get(i));
			}
		}

		protected <T> void resetData(ProcessingDataKey<T> key) {
			T value = getDataOrNull(key);
			if (value != null) {
				key.getValueResetAction().accept(value);
			}
		}

		public void reset() {
			emitterConsumers.clear();
			meshes.clear();
			resetData();
		}
	}
}
