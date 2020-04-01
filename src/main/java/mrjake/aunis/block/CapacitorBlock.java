package mrjake.aunis.block;

import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import mrjake.aunis.Aunis;
import mrjake.aunis.AunisProps;
import mrjake.aunis.stargate.StargateAbstractEnergyStorage;
import mrjake.aunis.tileentity.CapacitorTile;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;

public class CapacitorBlock extends Block {
	
	public static final String BLOCK_NAME = "capacitor_block";
	
	public CapacitorBlock() {		
		super(Material.IRON);
		
		setRegistryName(Aunis.ModID + ":" + BLOCK_NAME);
		setTranslationKey(Aunis.ModID + "." + BLOCK_NAME);
		
		setSoundType(SoundType.METAL); 
		setCreativeTab(Aunis.aunisCreativeTab);
		
		setDefaultState(blockState.getBaseState()
				.withProperty(AunisProps.FACING_HORIZONTAL, EnumFacing.NORTH));
		
		setHardness(3.0f);
		setHarvestLevel("pickaxe", 3);
	}
	
	
	// ------------------------------------------------------------------------
	// Block states
	
	@Override
	protected BlockStateContainer createBlockState() {
		return new BlockStateContainer(this, AunisProps.FACING_HORIZONTAL, AunisProps.LEVEL);
	}
	
	@Override
	public int getMetaFromState(IBlockState state) {		
		return state.getValue(AunisProps.FACING_HORIZONTAL).getHorizontalIndex();
	}
	
	@Override
	public IBlockState getStateFromMeta(int meta) {		
		return getDefaultState()
				.withProperty(AunisProps.FACING_HORIZONTAL, EnumFacing.byHorizontalIndex(meta & 0x03));
	}
	
	@Override
	public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
		StargateAbstractEnergyStorage capacitorEnergyStorage = (StargateAbstractEnergyStorage) world.getTileEntity(pos).getCapability(CapabilityEnergy.ENERGY, null);
		
		return state.withProperty(AunisProps.LEVEL, Math.round(capacitorEnergyStorage.getEnergyStored() / (float)capacitorEnergyStorage.getMaxEnergyStored() * 10));
	}
	
	// ------------------------------------------------------------------------
	// Block actions

	@Override
	public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		
		if (!world.isRemote && !player.isSneaking()) {
			CapacitorTile gateTile = (CapacitorTile) world.getTileEntity(pos);
			IEnergyStorage energyStorage = gateTile.getCapability(CapabilityEnergy.ENERGY, null);
			
			String energy = String.format("%,d", energyStorage.getEnergyStored());
			String capacity = String.format("%,d", energyStorage.getMaxEnergyStored());
			
			player.sendMessage(new TextComponentTranslation("chat.orlins.energyStored", energy, capacity));
		}
				
		return !player.isSneaking();
	}
	
	@Override
	public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
		EnumFacing facing = placer.getHorizontalFacing().getOpposite();
		state = state.withProperty(AunisProps.FACING_HORIZONTAL, facing);
		world.setBlockState(pos, state); 
		
		IEnergyStorage energyStorage = stack.getCapability(CapabilityEnergy.ENERGY, null);
		
		StargateAbstractEnergyStorage capacitorEnergyStorage = (StargateAbstractEnergyStorage) world.getTileEntity(pos).getCapability(CapabilityEnergy.ENERGY, null);
		capacitorEnergyStorage.setEnergyStored(energyStorage.getEnergyStored());
	}
	
	
	@Override
	public List<ItemStack> getDrops(IBlockAccess world, BlockPos pos, IBlockState state, int fortune) {
		StargateAbstractEnergyStorage capacitorEnergyStorage = (StargateAbstractEnergyStorage) world.getTileEntity(pos).getCapability(CapabilityEnergy.ENERGY, null);

		ItemStack stack = new ItemStack(this);
		((StargateAbstractEnergyStorage) stack.getCapability(CapabilityEnergy.ENERGY, null)).setEnergyStored(capacitorEnergyStorage.getEnergyStored());
				
		return Arrays.asList(stack);
	}
	
	@Override
    public boolean removedByPlayer(IBlockState state, World world, BlockPos pos, EntityPlayer player, boolean willHarvest) {
        if (willHarvest) return true; //If it will harvest, delay deletion of the block until after getDrops
        return super.removedByPlayer(state, world, pos, player, willHarvest);
    }
	
    @Override
    public void harvestBlock(World world, EntityPlayer player, BlockPos pos, IBlockState state, @Nullable TileEntity te, ItemStack tool) {
        super.harvestBlock(world, player, pos, state, te, tool);
        world.setBlockToAir(pos);
    }
	
	// ------------------------------------------------------------------------
	// Tile Entity
	
	@Override
	public boolean hasTileEntity(IBlockState state) {
		return true;
	}
	
	@Override
	public TileEntity createTileEntity(World world, IBlockState state) {
		return new CapacitorTile();
	}
	
	
	// ------------------------------------------------------------------------
	// Rendering
	
	@Override
	public BlockRenderLayer getRenderLayer() {
		return BlockRenderLayer.CUTOUT;
	}
}