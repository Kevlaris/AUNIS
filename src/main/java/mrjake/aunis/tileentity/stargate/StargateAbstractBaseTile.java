package mrjake.aunis.tileentity.stargate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;
import javax.vecmath.Vector2f;

import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import mrjake.aunis.Aunis;
import mrjake.aunis.AunisDamageSources;
import mrjake.aunis.AunisProps;
import mrjake.aunis.block.AunisBlocks;
import mrjake.aunis.capability.EnergyStorageUncapped;
import mrjake.aunis.config.AunisConfig;
import mrjake.aunis.packet.AunisPacketHandler;
import mrjake.aunis.packet.StateUpdatePacketToClient;
import mrjake.aunis.packet.StateUpdateRequestToServer;
import mrjake.aunis.packet.stargate.StargateRenderingUpdatePacketToServer;
import mrjake.aunis.particle.ParticleWhiteSmoke;
import mrjake.aunis.sound.AunisPositionedSoundEnum;
import mrjake.aunis.sound.AunisSoundHelper;
import mrjake.aunis.sound.EnumAunisSoundEvent;
import mrjake.aunis.stargate.AutoCloseManager;
import mrjake.aunis.stargate.EnumScheduledTask;
import mrjake.aunis.stargate.EnumStargateState;
import mrjake.aunis.stargate.EnumSymbol;
import mrjake.aunis.stargate.StargateAbstractMergeHelper;
import mrjake.aunis.stargate.StargateEnergyRequired;
import mrjake.aunis.stargate.StargateNetwork;
import mrjake.aunis.stargate.StargateNetwork.StargatePos;
import mrjake.aunis.stargate.teleportation.EventHorizon;
import mrjake.aunis.state.StargateAbstractRendererState;
import mrjake.aunis.state.StargateFlashState;
import mrjake.aunis.state.StargateRendererActionState;
import mrjake.aunis.state.StargateRendererActionState.EnumGateAction;
import mrjake.aunis.state.StargateVaporizeBlockParticlesRequest;
import mrjake.aunis.state.State;
import mrjake.aunis.state.StateProviderInterface;
import mrjake.aunis.state.StateTypeEnum;
import mrjake.aunis.tileentity.util.PreparableInterface;
import mrjake.aunis.tileentity.util.ScheduledTask;
import mrjake.aunis.tileentity.util.ScheduledTaskExecutorInterface;
import mrjake.aunis.util.AunisAxisAlignedBB;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.common.Optional;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@Optional.Interface(iface = "li.cil.oc.api.network.Environment", modid = "opencomputers")
public abstract class StargateAbstractBaseTile extends TileEntity implements StateProviderInterface, ITickable, ICapabilityProvider, ScheduledTaskExecutorInterface, Environment, PreparableInterface {
	
	// ------------------------------------------------------------------------
	// Stargate state
	
	protected EnumStargateState stargateState = EnumStargateState.IDLE;
	
	public final EnumStargateState getStargateState() {
		return stargateState;
	}
	
	private boolean isInitiating;
		
	/**
	 * Set on engageGate(). Indicates when the gate has been opened.
	 * 
	 * Used to calculate energy use while not loaded 
	 */
	public long gateOpenTime;
	
	/**
	 * How much energy gate already consumed to keep the connection alive
	 * 
	 * Subtracted from calculated energy use at the closeGate()
	 */
	public int energyConsumed;
	
	public final void updateEnergyStatus() {
		long ticks = world.getTotalWorldTime() - gateOpenTime;
		int energy = (int) (ticks * keepAliveCostPerTick);
				
		energy -= energyConsumed;
		
		energyStorage.extractEnergyUncapped(energy);
	}
	
	public void updateTargetGate() {
		StargatePos stargatePos = StargateNetwork.get(world).getStargate(dialedAddress);
		stargatePos.getWorld().getTileEntity(stargatePos.getPos()).markDirty();
	}
	
	protected void engageGate() {	
		gateOpenTime = world.getTotalWorldTime();
		energyConsumed = 0;
		
		stargateState = isInitiating ? EnumStargateState.ENGAGED_INITIATING : EnumStargateState.ENGAGED;
		eventHorizon.reset();
		
		playPositionedSound(AunisPositionedSoundEnum.WORMHOLE, true);
		
		markDirty();
	}
	
	protected void disconnectGate() {	
		stargateState = EnumStargateState.IDLE;
		getAutoCloseManager().reset();
		updateTargetGate();

		if (!(this instanceof StargateOrlinBaseTile))
			dialedAddress.clear();
		
		isFinalActive = false;
		
		markDirty();
	}
	
	public void onBlockBroken() {
		updateTargetGate();
		world.setBlockToAir(getGateCenterPos());
		
		updateMergeState(false, facing);
		StargateNetwork.get(world).removeStargate(gateAddress);
		
		playPositionedSound(AunisPositionedSoundEnum.WORMHOLE, false);
	}
	
	protected void sendRenderingUpdate(EnumGateAction gateAction, int chevronCount, boolean modifyFinal) {
		if (targetPoint != null) {
			AunisPacketHandler.INSTANCE.sendToAllTracking(new StateUpdatePacketToClient(pos, StateTypeEnum.RENDERER_UPDATE, new StargateRendererActionState(gateAction, chevronCount, modifyFinal)), targetPoint);
		}
	}
	
	// ------------------------------------------------------------------------
	// Stargate Network
	
	/**
	 * Instance of the {@link EventHorizon} for teleporting entities.
	 */
	protected EventHorizon eventHorizon;
	
	public AunisAxisAlignedBB getEventHorizonLocalBox() {
		return eventHorizon.getLocalBox();
	}
	
	/**
	 * Get the bounding box of the horizon.
	 * @param server Calling side.
	 * @return Horizon bounding box.
	 */
	protected abstract AunisAxisAlignedBB getHorizonTeleportBox(boolean server);

	private AutoCloseManager autoCloseManager;
	
	private AutoCloseManager getAutoCloseManager() {
		if (autoCloseManager == null)
			autoCloseManager = new AutoCloseManager(this);
		
		return autoCloseManager;
	}
	
	public void setMotionOfPassingEntity(int entityId, Vector2f motionVector) {
		eventHorizon.setMotion(entityId, motionVector);
	}
	
	public void teleportEntity(int entityId) {
		eventHorizon.teleportEntity(entityId);
	}
	
	public void removeEntity(int entityId) {
		eventHorizon.removeEntity(entityId);
	}
	
	public List<EnumSymbol> gateAddress = null;
	public List<EnumSymbol> dialedAddress = new ArrayList<EnumSymbol>();
	protected boolean isFinalActive;
	
	public int getEnteredSymbolsCount() {
		return dialedAddress.size();
	}
	
	private List<EnumSymbol> generateAddress() {			
		Random rand = new Random(pos.hashCode() * 31 + world.provider.getDimension());
		List<EnumSymbol> address = new ArrayList<EnumSymbol>(7); 
						
		while (address.size() < 7) {
			EnumSymbol symbol = EnumSymbol.valueOf( rand.nextInt(38) );
					
			if (!address.contains(symbol) && symbol != EnumSymbol.ORIGIN) {
				address.add(symbol);
			}
		}
		
		return address;
	}
		
	/**
	 * Checks whether the symbol can be added to the address.
	 * 
	 * @param symbol Symbol to be added
	 * @return
	 */
	public boolean canAddSymbol(EnumSymbol symbol) {
		if (dialedAddress.contains(symbol)) 
			return false;
				
		if (dialedAddress.size() == 7)
			return false;
		
		return true;
	}
	
	/**
	 * Adds symbol to address. Called from GateRenderingUpdatePacketToServer.
	 * 
	 * @param symbol Currently added symbol.
	 */
	protected void addSymbolToAddress(EnumSymbol symbol) {
		if (!canAddSymbol(symbol))
			throw new IllegalStateException("Cannot add that symbol");
		
		dialedAddress.add(symbol);
	}
	
	/**
	 * Called on receiving gate. Sets renderer's state
	 * 
	 * @param incomingAddress - Initializing gate's address
	 * @param dialedAddressSize - How many symbols are there pressed on the DHD
	 */
	public void incomingWormhole(List<EnumSymbol> incomingAddress, int dialedAddressSize) {
		Aunis.info("incoming size: " + dialedAddressSize);
		
		dialedAddress.clear();
		dialedAddress.addAll(incomingAddress);
		
		isFinalActive = true;
		
		AunisSoundHelper.playSoundEvent(world, pos, EnumAunisSoundEvent.CHEVRON_INCOMING, 0.5f);
		
		sendSignal(null, "stargate_incoming_wormhole", new Object[] { dialedAddressSize });
	}
	
	/**
	 * Called on BRB press. Initializes renderer's state
	 * 
	 * @param initiating - true if gate is initializing the connection
	 * @param incomingAddress - Source gate address
	 * @param eightChevronDial 
	 */
	public void openGate(boolean initiating, List<EnumSymbol> incomingAddress, boolean eightChevronDial) {
		stargateState = EnumStargateState.UNSTABLE;
		isInitiating = initiating;
		
		if (!isInitiating) {
			dialedAddress.clear();
			dialedAddress.addAll(eightChevronDial ? incomingAddress : incomingAddress.subList(0, 6));
			dialedAddress.add(EnumSymbol.ORIGIN);
			
			isFinalActive = true;
		}
		
		sendRenderingUpdate(EnumGateAction.OPEN_GATE, 0, false);
		
		addTask(new ScheduledTask(EnumScheduledTask.STARGATE_OPEN_SOUND));
		addTask(new ScheduledTask(EnumScheduledTask.STARGATE_HORIZON_LIGHT_BLOCK, EnumScheduledTask.STARGATE_OPEN_SOUND.waitTicks + 19 + getTicksPerHorizonSegment(true)));
		addTask(new ScheduledTask(EnumScheduledTask.STARGATE_HORIZON_WIDEN, EnumScheduledTask.STARGATE_OPEN_SOUND.waitTicks + 23 + getTicksPerHorizonSegment(true))); // 1.3s of the sound to the kill
		addTask(new ScheduledTask(EnumScheduledTask.STARGATE_ENGAGE));
		
		if (isInitiating) {
			((EnergyStorageUncapped) getEnergyStorage(openCost)).extractEnergyUncapped(openCost);
		}
		
		sendSignal(null, "stargate_open", new Object[] { isInitiating });
		
		markDirty();
	}
	
	/**
	 * Called either on pressing BRB on open gate or close command from a computer.
	 */
	public void closeGate() {
		stargateState = EnumStargateState.UNSTABLE;
		
		addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CLOSE, 62));
		sendSignal(null, "stargate_close", new Object[] {});
		
		AunisSoundHelper.playSoundEvent(world, pos, EnumAunisSoundEvent.GATE_CLOSE, 0.3f);
		sendRenderingUpdate(EnumGateAction.CLOSE_GATE, 0, false);
		playPositionedSound(AunisPositionedSoundEnum.WORMHOLE, false);
		
		horizonFlashTask = null;
		
		markDirty();
	}
	
	/**
	 * Called on the failed dialing.
	 */
	public void dialingFailed() {
		stargateState = EnumStargateState.FAILING;
				
		sendSignal(null, "stargate_failed", new Object[] {});
	
		horizonFlashTask = null;

		markDirty();
	}	
	
	// ------------------------------------------------------------------------
	// Ticking and loading

	protected abstract BlockPos getGateCenterPos();
	
	public void playPositionedSound(AunisPositionedSoundEnum soundEnum, boolean play) {
		if (world.isRemote)
			Aunis.proxy.playPositionedSoundClientSide(getGateCenterPos(), soundEnum, play);
		else
			AunisSoundHelper.playPositionedSound(world, getGateCenterPos(), soundEnum, play);
	}
	
	protected TargetPoint targetPoint;
	protected EnumFacing facing = EnumFacing.NORTH;
	
	@Override
	public void onLoad() {		
		if (!world.isRemote) {
			updateFacing(world.getBlockState(pos).getValue(AunisProps.FACING_HORIZONTAL), true);
			
			targetPoint = new TargetPoint(world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), 512);
			Aunis.ocWrapper.joinOrCreateNetwork(this);
			
			if (gateAddress == null) {
				gateAddress = generateAddress();
				markDirty();
								
//				if (StargateNetwork.get(world).checkForStargate(gateAddress))
//					Aunis.info(pos+"double address");
				if (StargateNetwork.get(world).checkForStargate(gateAddress))
					throw new IllegalStateException("Stargate with given address already exists");
				
				StargateNetwork.get(world).addStargate(gateAddress, world.provider.getDimension(), pos);
			}
		}
		
		else {
			AunisPacketHandler.INSTANCE.sendToServer(new StateUpdateRequestToServer(pos, Aunis.proxy.getPlayerClientSide(), StateTypeEnum.RENDERER_STATE));
		}
	}
	
	@Override
	public void update() {
		// Scheduled tasks
		ScheduledTask.iterate(scheduledTasks, world.getTotalWorldTime());		
		
		if (!world.isRemote) {
			// Event horizon teleportation			
			if (stargateState == EnumStargateState.ENGAGED_INITIATING) {
				eventHorizon.scheduleTeleportation(StargateNetwork.get(world).getStargate(dialedAddress));
			}
						
			// Not initiating
			if (stargateState == EnumStargateState.ENGAGED) {
				getAutoCloseManager().update(StargateNetwork.get(world).getStargate(dialedAddress));
//				Aunis.info(scheduledTasks.toString());
			}
						
			if (horizonFlashTask != null && horizonFlashTask.isActive()) {
				horizonFlashTask.update(world.getTotalWorldTime());
			}
			
			// Event horizon killing
			if (horizonKilling) {	
				List<EntityLivingBase> entities = new ArrayList<EntityLivingBase>();
				List<BlockPos> blocks = new ArrayList<BlockPos>();
				
				// Get all blocks and entities inside the kawoosh
				for (int i=0; i<horizonSegments; i++) {
					AunisAxisAlignedBB gBox = localKillingBoxes.get(i).offset(pos);
					
					entities.addAll(world.getEntitiesWithinAABB(EntityLivingBase.class, gBox));
					
//					Aunis.info(new AxisAlignedBB((int)Math.floor(gBox.minX), (int)Math.floor(gBox.minY+1), (int)Math.floor(gBox.minZ), (int)Math.ceil(gBox.maxX-1), (int)Math.ceil(gBox.maxY-1), (int)Math.ceil(gBox.maxZ-1)).toString());
					for (BlockPos bPos : BlockPos.getAllInBox((int)Math.floor(gBox.minX), (int)Math.floor(gBox.minY), (int)Math.floor(gBox.minZ), (int)Math.ceil(gBox.maxX)-1, (int)Math.ceil(gBox.maxY)-1, (int)Math.ceil(gBox.maxZ)-1))
						blocks.add(bPos);					
				}
				
				// Get all entities inside the gate
				for (AunisAxisAlignedBB lBox : localInnerEntityBoxes)
					entities.addAll(world.getEntitiesWithinAABB(EntityLivingBase.class, lBox.offset(pos)));
				
				// Get all blocks inside the gate
				for (AunisAxisAlignedBB lBox : localInnerBlockBoxes) {
					AunisAxisAlignedBB gBox = lBox.offset(pos);
					
					for (BlockPos bPos : BlockPos.getAllInBox((int)gBox.minX, (int)gBox.minY, (int)gBox.minZ, (int)gBox.maxX-1, (int)gBox.maxY-1, (int)gBox.maxZ-1))
						blocks.add(bPos);
				}
				
				// Kill them
				for (EntityLivingBase entity : entities) {
					entity.attackEntityFrom(AunisDamageSources.DAMAGE_EVENT_HORIZON, 20);
					AunisPacketHandler.INSTANCE.sendToAllTracking(new StateUpdatePacketToClient(pos, StateTypeEnum.STARGATE_VAPORIZE_BLOCK_PARTICLES, new StargateVaporizeBlockParticlesRequest(entity.getPosition())), targetPoint);
				}
				
				// Vaporize them
				for (BlockPos dPos : blocks) {
					if (!dPos.equals(getGateCenterPos())) {
						if (!world.isAirBlock(dPos)) {
							world.setBlockToAir(dPos);
							AunisPacketHandler.INSTANCE.sendToAllTracking(new StateUpdatePacketToClient(pos, StateTypeEnum.STARGATE_VAPORIZE_BLOCK_PARTICLES, new StargateVaporizeBlockParticlesRequest(dPos)), targetPoint);
						}
					}
				}
			}
			
			
			/*
			 * Draw power (engaged)
			 * 
			 * If initiating
			 * 	True: Extract energy each tick
			 * 	False: Update the source gate about consumed energy each second
			 */
			if (stargateState.engaged()) {
				if (stargateState.initiating()) {

					// If we have enough energy(minimal DHD operable)
					if (getEnergyStorage(AunisConfig.powerConfig.dhdMinimalEnergy * 2) != null) {
						IEnergyStorage energyStorage = getEnergyStorage(keepAliveCostPerTick);
						
						if (energyStorage != null) {
							int sec = keepAliveCostPerTick*20;
	
							/*
							 * If energy can sustain connection for less than AunisConfig.powerConfig.instabilitySeconds seconds
							 * Start flickering
							 */
							
							// Horizon becomes unstable
							if (horizonFlashTask == null && energyStorage.getEnergyStored() < sec*AunisConfig.powerConfig.instabilitySeconds) {
								resetFlashingSequence();
								
								horizonFlashTask = new ScheduledTask(EnumScheduledTask.HORIZON_FLASH, (int) (Math.random() * 40) + 5);
							}
							
							// Horizon becomes stable
							if (horizonFlashTask != null && energyStorage.getEnergyStored() > sec*AunisConfig.powerConfig.instabilitySeconds) {
								horizonFlashTask = null;
								isCurrentlyUnstable = false;
								
								updateFlashState(false);
							}
							
							energyConsumed += ((EnergyStorageUncapped) energyStorage).extractEnergyUncapped(keepAliveCostPerTick);
							
							markDirty();
	//						Aunis.info("Stargate energy: " + energyStorage.getEnergyStored() + " / " + energyStorage.getMaxEnergyStored() + "\t\tAlive for: " + (float)(energyStorage.getEnergyStored())/keepAliveCostPerTick/20);
						}
						
						else
							StargateRenderingUpdatePacketToServer.closeGatePacket(this, false);
					}
					
					else {
						StargateRenderingUpdatePacketToServer.closeGatePacket(this, false);
					}
				}
				
				else {
					if (world.getTotalWorldTime() % 20 == 0 && dialedAddress.size() > 0) {
						StargatePos sourcePos = StargateNetwork.get(world).getStargate(dialedAddress);
						
						// Only if not loaded
						if (!sourcePos.getWorld().isBlockLoaded(sourcePos.getPos())) {					
							StargateMilkyWayBaseTile sourceTile = (StargateMilkyWayBaseTile) sourcePos.getWorld().getTileEntity(sourcePos.getPos());
							
							sourceTile.updateEnergyStatus();
						}
					}
				}
			}
		}
	}
	
	@Override
	public void onChunkUnload() {
		if (node != null)
			node.remove();
		
		super.onChunkUnload();
	}

	@Override
	public void invalidate() {
		if (node != null)
			node.remove();
		
		super.invalidate();
	}
	
	@Override
	public void rotate(Rotation rotation) {
		IBlockState state = world.getBlockState(pos);
		
		EnumFacing facing = state.getValue(AunisProps.FACING_HORIZONTAL);
		world.setBlockState(pos, state.withProperty(AunisProps.FACING_HORIZONTAL, rotation.rotate(facing)));
	}
	
	// ------------------------------------------------------------------------
	// Killing and block vaporizing
	
	/**
	 * Gets full {@link AxisAlignedBB} of the killing area.
	 * @param server Calling side.
	 * @return Approximate kawoosh size.
	 */
	protected abstract AunisAxisAlignedBB getHorizonKillingBox(boolean server);
	
	/**
	 * How many segments should the exclusion zone have.
	 * @param server Calling side.
	 * @return Count of subsegments of the killing box.
	 */
	protected abstract int getHorizonSegmentCount(boolean server);
	
	/**
	 * The event horizon in the gate also should kill
	 * and vaporize everything
	 * @param server Calling side.
	 * @return List of {@link AxisAlignedBB} for the inner gate area.
	 */
	protected abstract List<AunisAxisAlignedBB> getGateVaporizingBoxes(boolean server);
	
	/**
	 * How many ticks should the {@link StargateAbstractBaseTile} wait to perform
	 * next update to the size of the killing box.
	 * @param server Calling side
	 */
	protected int getTicksPerHorizonSegment(boolean server) {
		return 12 / getHorizonSegmentCount(server);
	}
	
	/**
	 * Contains all the subboxes to be activated with the kawoosh.
	 * On the server needs to be offsetted by the {@link TileEntity#getPos()}
	 */
	protected List<AunisAxisAlignedBB> localKillingBoxes;
	
	public List<AunisAxisAlignedBB> getLocalKillingBoxes() {
		return localKillingBoxes;
	}
	
	/**
	 * Contains all boxes of the inner part of the gate.
	 * Full blocks. Used for destroying blocks.
	 * On the server needs to be offsetted by the {@link TileEntity#getPos()}
	 */
	protected List<AunisAxisAlignedBB> localInnerBlockBoxes;
	
	public List<AunisAxisAlignedBB> getLocalInnerBlockBoxes() {
		return localInnerBlockBoxes;
	}
	
	/**
	 * Contains all boxes of the inner part of the gate.
	 * Not full blocks. Used for entity killing.
	 * On the server needs to be offsetted by the {@link TileEntity#getPos()}
	 */
	protected List<AunisAxisAlignedBB> localInnerEntityBoxes;
	
	public List<AunisAxisAlignedBB> getLocalInnerEntityBoxes() {
		return localInnerEntityBoxes;
	}
	
	private boolean horizonKilling = false;
	private int horizonSegments = 0;

	// ------------------------------------------------------------------------
	// Rendering
	
	StargateAbstractRendererState rendererStateClient;
			
	protected abstract StargateAbstractRendererState getRendererStateServer();
	protected abstract StargateAbstractRendererState createRendererStateClient();

	public StargateAbstractRendererState getRendererStateClient() {
		return rendererStateClient;
	}
	
	protected void setRendererStateClient(StargateAbstractRendererState rendererState) {
		this.rendererStateClient = rendererState;
		
		playPositionedSound(AunisPositionedSoundEnum.WORMHOLE, rendererState.doEventHorizonRender);
		addTask(new ScheduledTask(EnumScheduledTask.STARGATE_LIGHTING_UPDATE_CLIENT, 10));
	}
	
	public void updateFacing(EnumFacing facing, boolean server) {
		this.facing = facing;
		this.eventHorizon = new EventHorizon(world, pos, facing, getHorizonTeleportBox(server));
		
		AunisAxisAlignedBB kBox = getHorizonKillingBox(server);
		double width = kBox.maxZ - kBox.minZ;
		width /= getHorizonSegmentCount(server);
		
		localKillingBoxes = new ArrayList<AunisAxisAlignedBB>(getHorizonSegmentCount(server));
		for (int i=0; i<getHorizonSegmentCount(server); i++) {
			AunisAxisAlignedBB box = new AunisAxisAlignedBB(kBox.minX, kBox.minY, kBox.minZ + width*i, kBox.maxX, kBox.maxY, kBox.minZ + width*(i+1));
			box = box.rotate(facing).offset(0.5, 0, 0.5);
			
			localKillingBoxes.add(box);
		}
		
		localInnerBlockBoxes = new ArrayList<AunisAxisAlignedBB>(3);
		localInnerEntityBoxes = new ArrayList<AunisAxisAlignedBB>(3);
		for (AunisAxisAlignedBB lBox : getGateVaporizingBoxes(server)) {
			localInnerBlockBoxes.add(lBox.rotate(facing).offset(0.5, 0, 0.5));
			localInnerEntityBoxes.add(lBox.grow(0, 0, -0.25).rotate(facing).offset(0.5, 0, 0.5));
		}
	}
	
	@Override
	public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newSate) {
		return oldState.getBlock() != newSate.getBlock();
	}
	
	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		return new AxisAlignedBB(getPos().add(-7, 0, -7), getPos().add(7, 12, 7));
	}
		
	@Override
	public double getMaxRenderDistanceSquared() {
		return 65536;
	}	
	
	
	// ------------------------------------------------------------------------
	// Merging
	
	private boolean isMerged;
	
	public final boolean isMerged() {
		return isMerged;
	}
	
	/**
	 * Function called when {@link this#updateMergeState(boolean, IBlockState)} is
	 * called with {@code true} parameter(multiblock valid).
	 */
	protected abstract void mergeGate();
	
	/**
	 * Function called when {@link this#updateMergeState(boolean, IBlockState)} is
	 * called with {@code false} parameter(multiblock not valid).
	 */
	protected abstract void unmergeGate();
	
	/**
	 * @return Appropriate merge helper
	 */
	protected abstract StargateAbstractMergeHelper getMergeHelper();
	
	/**
	 * Checks gate's merge state
	 * 
	 * @param shouldBeMerged - True if gate's multiblock structure is valid
	 * @param facing Facing of the base block.
	 */
	public final void updateMergeState(boolean shouldBeMerged, EnumFacing facing) {		
		this.isMerged = shouldBeMerged;
		
		if (!shouldBeMerged) {
			unmergeGate();
			
			if (stargateState.engaged()) {
				StargateRenderingUpdatePacketToServer.closeGatePacket(this, true);
			}
		}
		
		else {
			mergeGate();
		}
		
		IBlockState actualState = world.getBlockState(pos);
		
		// When the block is destroyed, there will be air in this place and we cannot set it's block state
		if (getMergeHelper().getBaseMatcher().apply(actualState)) {
			world.setBlockState(pos, actualState.withProperty(AunisProps.RENDER_BLOCK, !shouldBeMerged), 2);
		}
		
		getMergeHelper().updateMembersMergeStatus(world, pos, facing, shouldBeMerged);
		
		markDirty();
	}
	
	
	// ------------------------------------------------------------------------
	// AutoClose
	
	public final void entityPassing(boolean isPlayer, boolean inbound) {		
		if (isPlayer) {
			getAutoCloseManager().playerPassing();
			markDirty();
		}
		
		sendSignal(null, "stargate_traveler", new Object[] {inbound, isPlayer});
	}

	
	// -----------------------------------------------------------------
	// Horizon flashing
	private ScheduledTask horizonFlashTask;

	private int flashIndex = 0;
	private boolean isCurrentlyUnstable = false;
	
	private void resetFlashingSequence() {
		flashIndex = 0;
		isCurrentlyUnstable = false;
	}
	
	private void updateFlashState(boolean flash) {
		StargatePos targetPos = StargateNetwork.get(world).getStargate(dialedAddress);
		BlockPos tPos = targetPos.getPos();
				
		AunisPacketHandler.INSTANCE.sendToAllTracking(new StateUpdatePacketToClient(pos, StateTypeEnum.FLASH_STATE, new StargateFlashState(isCurrentlyUnstable)), targetPoint);
		AunisPacketHandler.INSTANCE.sendToAllTracking(new StateUpdatePacketToClient(tPos, StateTypeEnum.FLASH_STATE, new StargateFlashState(isCurrentlyUnstable)), new TargetPoint(targetPos.getDimension(), tPos.getX(), tPos.getY(), tPos.getZ(), 512));
	}
	
	
	// ------------------------------------------------------------------------
	// States

	@Override
	public State getState(StateTypeEnum stateType) {
		switch (stateType) {
			case RENDERER_STATE:
				return getRendererStateServer();
				
			default:
				return null;
		}
	}

	@Override
	public State createState(StateTypeEnum stateType) {
		switch (stateType) {
			case RENDERER_STATE:
				return createRendererStateClient();
		
			case RENDERER_UPDATE:
				return new StargateRendererActionState();
				
			case STARGATE_VAPORIZE_BLOCK_PARTICLES:
				return new StargateVaporizeBlockParticlesRequest();
				
			case FLASH_STATE:
				return new StargateFlashState();
				
			default:
				return null;
		}
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void setState(StateTypeEnum stateType, State state) {
		switch (stateType) {
			case RENDERER_STATE:
//				getRendererStargate().setRendererState((StargateRendererStateBase) state);
				EnumFacing facing = world.getBlockState(pos).getValue(AunisProps.FACING_HORIZONTAL);
				
				setRendererStateClient(((StargateAbstractRendererState) state).initClient(pos, facing));
				updateFacing(facing, false);
				
				break;
				
			case RENDERER_UPDATE:
				switch (((StargateRendererActionState) state).action) {
					case OPEN_GATE:
						getRendererStateClient().horizonSegments = 0;
						getRendererStateClient().openGate(world.getTotalWorldTime());
						break;
						
					case CLOSE_GATE:
						getRendererStateClient().closeGate(world.getTotalWorldTime());						
						break;
						
					case STARGATE_HORIZON_WIDEN:
						getRendererStateClient().horizonSegments++;
						break;
						
					case STARGATE_HORIZON_SHRINK:
						getRendererStateClient().horizonSegments--;
						break;
						
					default:
						break;					
				}
				
				break;
		
			case STARGATE_VAPORIZE_BLOCK_PARTICLES:
				BlockPos b = ((StargateVaporizeBlockParticlesRequest) state).block;
				
				for (int i=0; i<20; i++) {
					Minecraft.getMinecraft().effectRenderer.addEffect(new ParticleWhiteSmoke(world, b.getX() + (Math.random()-0.5), b.getY(), b.getZ() + (Math.random()-0.5), 0, 0, false));
				}
				
				break;
				
			case FLASH_STATE:
				getRendererStateClient().horizonUnstable = ((StargateFlashState) state).flash;
				break;
				
			default:
				break;
		}
	}
	
	// ------------------------------------------------------------------------
	// Scheduled tasks
	
	/**
	 * List of scheduled tasks to be performed on {@link ITickable#update()}.
	 */
	private List<ScheduledTask> scheduledTasks = new ArrayList<>();
	
	@Override
	public void addTask(ScheduledTask scheduledTask) {		
		scheduledTask.setExecutor(this);
		scheduledTask.setTaskCreated(world.getTotalWorldTime());
		
		scheduledTasks.add(scheduledTask);		
		markDirty();
	}	
	
	@Override
	public void executeTask(EnumScheduledTask scheduledTask, NBTTagCompound customData) {		
		switch (scheduledTask) {
			case STARGATE_OPEN_SOUND:
				AunisSoundHelper.playSoundEvent(world, pos, EnumAunisSoundEvent.GATE_OPEN, 0.3f);
				break;
				
			case STARGATE_HORIZON_LIGHT_BLOCK:
				world.setBlockState(getGateCenterPos(), AunisBlocks.invisibleBlock.getDefaultState().withProperty(AunisProps.HAS_COLLISIONS, false));
				Aunis.info("place");
				
				break;
				
			case STARGATE_HORIZON_WIDEN:
				if (!horizonKilling)
					horizonKilling = true;
				
				horizonSegments++;
				AunisPacketHandler.INSTANCE.sendToAllTracking(new StateUpdatePacketToClient(pos, StateTypeEnum.RENDERER_UPDATE, StargateRendererActionState.STARGATE_HORIZON_WIDEN_ACTION), targetPoint);
				
				if (horizonSegments < getHorizonSegmentCount(true))
					addTask(new ScheduledTask(EnumScheduledTask.STARGATE_HORIZON_WIDEN, getTicksPerHorizonSegment(true)));
				else
					addTask(new ScheduledTask(EnumScheduledTask.STARGATE_HORIZON_SHRINK, getTicksPerHorizonSegment(true) + 12));
				
				break;
				
			case STARGATE_HORIZON_SHRINK:
				horizonSegments--;
				AunisPacketHandler.INSTANCE.sendToAllTracking(new StateUpdatePacketToClient(pos, StateTypeEnum.RENDERER_UPDATE, StargateRendererActionState.STARGATE_HORIZON_SHRINK_ACTION), targetPoint);
				
				if (horizonSegments > 0)
					addTask(new ScheduledTask(EnumScheduledTask.STARGATE_HORIZON_SHRINK, getTicksPerHorizonSegment(true) + 1));
				else
					horizonKilling = false;
				
				markDirty();
					
				break;
				
			case STARGATE_CLOSE:
				world.setBlockToAir(getGateCenterPos());
				disconnectGate();
				break;
				
			case STARGATE_ENGAGE:
				engageGate();
				
				break;
				
			case STARGATE_LIGHTING_UPDATE_CLIENT:
				world.notifyLightSet(getGateCenterPos());
				world.checkLightFor(EnumSkyBlock.BLOCK, getGateCenterPos());
								
				break;
				
			case HORIZON_FLASH:
				isCurrentlyUnstable ^= true;
				
				if (isCurrentlyUnstable) {
					flashIndex++;
					
					if (flashIndex == 1)
						AunisSoundHelper.playSoundEvent(world, pos, EnumAunisSoundEvent.WORMHOLE_FLICKER, 0.5f);
					
					// Schedule change into stable state
					horizonFlashTask = new ScheduledTask(EnumScheduledTask.HORIZON_FLASH, (int)(Math.random() * 3) + 3);
				}
				
				else {
					if (flashIndex == 1)
						// Schedule second flash
						horizonFlashTask = new ScheduledTask(EnumScheduledTask.HORIZON_FLASH, (int)(Math.random() * 4) + 1);
					
					else {
						// Schedule next flash sequence
						horizonFlashTask = new ScheduledTask(EnumScheduledTask.HORIZON_FLASH, (int)(Math.random() * 40) + 5);
						
						resetFlashingSequence();
					}
				}
				
				updateFlashState(isCurrentlyUnstable);
				
				markDirty();				
				break;
		
			default:
				throw new UnsupportedOperationException("EnumScheduledTask."+scheduledTask.name()+" not implemented on "+this.getClass().getName());
		}
	}
	
	
	// -----------------------------------------------------------------
	// Power system
	
	private int openCost = 0;
	private int keepAliveCostPerTick = 0;
	
	protected EnergyStorageUncapped energyStorage = new EnergyStorageUncapped(AunisConfig.powerConfig.stargateEnergyStorage, AunisConfig.powerConfig.stargateMaxEnergyTransfer) {
		protected void onEnergyChanged() {			
			markDirty();
		};
	};
	
	/**
	 * Unifies energy consumption methods
	 * The order is as follows:
	 *   - Gate internal buffer
	 *   - DHD Crystal
	 *   
	 * @param minEnergy - minimal energy 
	 * @return First IEnergyStorage that has enough energy, CAN BE NULL if no source can provide such energy
	 */
	@Nullable
	protected IEnergyStorage getEnergyStorage(int minEnergy) {
		if (energyStorage.getEnergyStored() >= minEnergy)
			return energyStorage;
		
		return null;
	}
	
	/**
	 * Checks if this {@link StargateAbstractBaseTile} can provide enough energy.
	 * 
	 * @param energy Energy required.
	 * @return Will it suffice?
	 */
	public boolean hasEnergy(int energy) {
		return getEnergyStorage(energy) != null;
	}
	
	/**
	 * Checks is gate has sufficient power to dial across specified distance and dimension
	 * It also sets energy draw for (possibly) outgoing wormhole
	 * 
	 * @param distance - distance in blocks to target gate
	 * @param targetWorld - target world, used for multiplier
	 */
	public boolean hasEnergyToDial(StargateEnergyRequired energyRequired) {		
//		Aunis.info("Energy required to dial [distance="+distance+", mul="+multiplier+"] = " + energy + " / keepAlive: "+(keepAlive*20)+"/s, stored: " + (getEnergyStorage(1) != null ? getEnergyStorage(1).getEnergyStored() : 0));
				
		if (getEnergyStorage(energyRequired.energyToOpen) != null) {
			this.openCost = energyRequired.energyToOpen;
			this.keepAliveCostPerTick = energyRequired.keepAlive;
		
			markDirty();
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
		return (capability == CapabilityEnergy.ENERGY) || super.hasCapability(capability, facing);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
		if (capability == CapabilityEnergy.ENERGY) {
			return (T)energyStorage;
		}
		
		return super.getCapability(capability, facing);
	}
	
	
	// ------------------------------------------------------------------------
	// NBT
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound compound) {				
		if (gateAddress != null) {
			for (int i=0; i<7; i++) {
				compound.setInteger("symbol"+i, gateAddress.get(i).id);
			}
		}
				
		compound.setInteger("dialedAddressLength", dialedAddress.size());
		
		for (int i=0; i<dialedAddress.size(); i++) {
			compound.setInteger("dialedSymbol"+i, dialedAddress.get(i).id);
		}
			
		compound.setBoolean("isFinalActive", isFinalActive);
		compound.setBoolean("isMerged", isMerged);
		compound.setTag("autoCloseManager", getAutoCloseManager().serializeNBT());
		compound.setTag("energyStorage", energyStorage.serializeNBT());
		
		compound.setInteger("openCost", openCost);
		compound.setInteger("keepAliveCostPerTick", keepAliveCostPerTick);
		
		compound.setLong("gateOpenTime", gateOpenTime);
		compound.setInteger("energyConsumed", energyConsumed);
		
		if (stargateState != null)
			compound.setInteger("stargateState", stargateState.id);
		
		compound.setTag("scheduledTasks", ScheduledTask.serializeList(scheduledTasks));
		
		if (node != null) {
			NBTTagCompound nodeCompound = new NBTTagCompound();
			node.save(nodeCompound);
			
			compound.setTag("node", nodeCompound);
		}
		
		compound.setBoolean("horizonKilling", horizonKilling);
		compound.setInteger("horizonSegments", horizonSegments);
		
		return super.writeToNBT(compound);
	}
	
	@Override
	public void readFromNBT(NBTTagCompound compound) {		
		if (compound.hasKey("symbol0")) {		
			gateAddress = new ArrayList<EnumSymbol>();
			
			for (int i=0; i<7; i++) {
				int id = compound.getInteger("symbol"+i);
				gateAddress.add( EnumSymbol.valueOf(id) );
			}
		}
				
		dialedAddress.clear();
		int dialedAddressLength = compound.getInteger("dialedAddressLength");
		
		for (int i=0; i<dialedAddressLength; i++) {
			dialedAddress.add( EnumSymbol.valueOf(compound.getInteger("dialedSymbol"+i)) );
		}
		
		isFinalActive = compound.getBoolean("isFinalActive");
		isMerged = compound.getBoolean("isMerged");
		getAutoCloseManager().deserializeNBT(compound.getCompoundTag("autoCloseManager"));
		
		try {
			ScheduledTask.deserializeList(compound.getCompoundTag("scheduledTasks"), scheduledTasks, this);
		}
		
		catch (NullPointerException | IndexOutOfBoundsException | ClassCastException e) {
			Aunis.info("Exception at reading NBT");
			Aunis.info("If loading world used with previous version and nothing game-breaking doesn't happen, please ignore it");

			e.printStackTrace();
		}
		
		energyStorage.deserializeNBT(compound.getCompoundTag("energyStorage"));
		
		this.openCost = compound.getInteger("openCost");
		this.keepAliveCostPerTick = compound.getInteger("keepAliveCostPerTick");
		
		this.gateOpenTime = compound.getLong("gateOpenTime");
		this.energyConsumed = compound.getInteger("energyConsumed");
		
		stargateState = EnumStargateState.valueOf(compound.getInteger("stargateState"));
		if (stargateState == null)
			stargateState = EnumStargateState.IDLE;
				
		if (node != null && compound.hasKey("node"))
			node.load(compound.getCompoundTag("node"));
		
		horizonKilling = compound.getBoolean("horizonKilling");
		horizonSegments = compound.getInteger("horizonSegments");
		
		super.readFromNBT(compound);
	}
	
	@Override
	public void prepare() {
		gateAddress = null;
	}
	
	// ------------------------------------------------------------------------
	// OpenComputers
	
	// ------------------------------------------------------------
	// Node-related work
	private Node node = Aunis.ocWrapper.createNode(this, "stargate");
	
	@Override
	@Optional.Method(modid = "opencomputers")
	public Node node() {
		return node;
	}

	@Override
	@Optional.Method(modid = "opencomputers")
	public void onConnect(Node node) {}

	@Override
	@Optional.Method(modid = "opencomputers")
	public void onDisconnect(Node node) {}

	@Override
	@Optional.Method(modid = "opencomputers")
	public void onMessage(Message message) {}
	
	public void sendSignal(Object context, String name, Object... params) {
		Aunis.ocWrapper.sendSignalToReachable(node, (Context) context, name, params);
	}
	
	// ------------------------------------------------------------
	// Methods
	// function(arg:type[, optionArg:type]):resultType; Description.
	@Optional.Method(modid = "opencomputers")
	@Callback(getter = true)
	public Object[] stargateAddress(Context context, Arguments args) {
		return new Object[] {isMerged ? gateAddress : null};
	}

	@Optional.Method(modid = "opencomputers")
	@Callback(getter = true)
	public Object[] dialedAddress(Context context, Arguments args) {
		return new Object[] {isMerged ? dialedAddress : null};
	}
}
