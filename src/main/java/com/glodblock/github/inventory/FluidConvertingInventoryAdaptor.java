package com.glodblock.github.inventory;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import appeng.api.config.FuzzyMode;
import appeng.api.config.InsertionMode;
import appeng.api.parts.IPart;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.parts.p2p.PartP2PLiquids;
import appeng.tile.misc.TileInterface;
import appeng.tile.networking.TileCableBus;
import appeng.util.InventoryAdaptor;
import appeng.util.inv.IInventoryDestination;
import appeng.util.inv.ItemSlot;
import cofh.api.transport.IItemDuct;

import com.glodblock.github.common.Config;
import com.glodblock.github.common.item.ItemFluidPacket;
import com.glodblock.github.common.parts.PartFluidExportBus;
import com.glodblock.github.common.parts.PartFluidInterface;
import com.glodblock.github.common.tile.TileFluidInterface;
import com.glodblock.github.util.Ae2Reflect;
import com.glodblock.github.util.BlockPos;
import com.glodblock.github.util.ModAndClassUtil;
import com.glodblock.github.util.Util;

public class FluidConvertingInventoryAdaptor extends InventoryAdaptor {

    // facing is the target TE direction
    // |T|-facing->|I|
    public FluidConvertingInventoryAdaptor(@Nullable InventoryAdaptor invItems, @Nullable IFluidHandler invFluids,
            ForgeDirection facing, BlockPos pos, boolean isOnmi) {
        this.invItems = invItems;
        this.invFluids = invFluids;
        this.side = facing;
        this.posInterface = pos;
        this.onmi = isOnmi;
        this.selfInterface = getInterfaceTE(pos.getTileEntity(), facing.getOpposite());
    }

    private final InventoryAdaptor invItems;
    private final IFluidHandler invFluids;
    private final ForgeDirection side;
    private final BlockPos posInterface;
    @Nullable
    private final IInterfaceHost selfInterface;
    private final boolean onmi;

    public static InventoryAdaptor wrap(TileEntity capProvider, ForgeDirection face) {
        // sometimes i wish 1.7.10 has cap system.
        TileEntity inter = capProvider.getWorldObj().getTileEntity(
                capProvider.xCoord + face.offsetX,
                capProvider.yCoord + face.offsetY,
                capProvider.zCoord + face.offsetZ);
        if (!Config.noFluidPacket && !(inter instanceof TileFluidInterface
                || Util.getPart(inter, face.getOpposite()) instanceof PartFluidInterface
                || Util.getPart(inter, face.getOpposite()) instanceof PartFluidExportBus))
            return InventoryAdaptor.getAdaptor(capProvider, face);
        InventoryAdaptor item = InventoryAdaptor.getAdaptor(capProvider, face);
        IFluidHandler fluid = capProvider instanceof IFluidHandler ? (IFluidHandler) capProvider : null;
        boolean onmi = false;
        if (inter instanceof TileInterface) {
            onmi = ((TileInterface) inter).getTargets().size() > 1;
        }
        return new FluidConvertingInventoryAdaptor(item, fluid, face, new BlockPos(inter), onmi);
    }

    public ItemStack addItems(ItemStack toBeAdded, InsertionMode insertionMode) {
        FluidStack fluid = Util.getFluidFromVirtual(toBeAdded);
        if (!this.onmi) {
            if (!checkValidSide(
                    this.posInterface.getOffSet(this.side.getOpposite()).getTileEntity(),
                    this.side.getOpposite())) {
                return toBeAdded;
            }
            if (fluid != null) {
                int filled = fillSideFluid(fluid, this.invFluids, this.side, true);
                fluid.amount -= filled;
                return ItemFluidPacket.newStack(fluid);
            } else {
                ItemStack notFilled = fillSideItem(toBeAdded, this.invItems, insertionMode, true);
                if (notFilled != null) {
                    // Fill EIO Conduit at last.
                    return fillEIOConduit(
                            notFilled,
                            this.posInterface.getOffSet(this.side.getOpposite()).getTileEntity(),
                            this.side);
                }
                return null;
            }
        } else {
            if (fluid != null) {
                for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                    if (fluid.amount <= 0) {
                        return null;
                    }
                    if (!checkValidSide(this.posInterface.getOffSet(dir).getTileEntity(), dir)) {
                        continue;
                    }
                    int filled = fillSideFluid(fluid, getSideFluid(dir), dir.getOpposite(), true);
                    fluid.amount -= filled;
                }
                return ItemFluidPacket.newStack(fluid);
            } else {
                ItemStack item = toBeAdded.copy();
                for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                    if (item == null || item.stackSize <= 0) {
                        return null;
                    }
                    if (!checkValidSide(this.posInterface.getOffSet(dir).getTileEntity(), dir)) {
                        continue;
                    }
                    item = fillSideItem(item, getSideItem(dir), insertionMode, true);
                }
                // Fill EIO Conduit at last.
                for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                    if (item == null || item.stackSize <= 0) {
                        return null;
                    }
                    if (!checkValidSide(this.posInterface.getOffSet(dir).getTileEntity(), dir)) {
                        continue;
                    }
                    item = fillEIOConduit(item, this.posInterface.getOffSet(dir).getTileEntity(), dir.getOpposite());
                }
                return item;
            }
        }
    }

    @Override
    public ItemStack addItems(ItemStack toBeAdded) {
        return addItems(toBeAdded, InsertionMode.DEFAULT);
    }

    @Override
    public ItemStack simulateAdd(ItemStack toBeSimulated) {
        return simulateAdd(toBeSimulated, InsertionMode.DEFAULT);
    }

    @Override
    public ItemStack simulateAdd(ItemStack toBeSimulated, InsertionMode insertionMode) {
        FluidStack fluid = Util.getFluidFromVirtual(toBeSimulated);
        if (!this.onmi) {
            if (!checkValidSide(
                    this.posInterface.getOffSet(this.side.getOpposite()).getTileEntity(),
                    this.side.getOpposite())) {
                return toBeSimulated;
            }
            if (fluid != null) {
                int filled = fillSideFluid(fluid, this.invFluids, this.side, false);
                fluid.amount -= filled;
                return ItemFluidPacket.newStack(fluid);
            } else {
                // Assert EIO conduit can hold all item, as it is the origin practice in AE2
                if (isConduit(this.posInterface.getOffSet(this.side.getOpposite()).getTileEntity())) {
                    return null;
                } else {
                    return fillSideItem(toBeSimulated, this.invItems, insertionMode, false);
                }
            }
        } else {
            // In onmi mode, fluid/item only need to be partly inserted.
            boolean sus = false;
            if (fluid != null) {
                for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                    if (!checkValidSide(this.posInterface.getOffSet(dir).getTileEntity(), dir)) {
                        continue;
                    }
                    int filled = fillSideFluid(fluid, getSideFluid(dir), dir.getOpposite(), false);
                    if (filled > 0) {
                        sus = true;
                        break;
                    }
                }
            } else {
                for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                    if (!checkValidSide(this.posInterface.getOffSet(dir).getTileEntity(), dir)) {
                        continue;
                    }
                    // Assert EIO conduit can hold all item, as it is the origin practice in AE2
                    if (isConduit(this.posInterface.getOffSet(dir).getTileEntity())) {
                        return null;
                    }
                    ItemStack notFilled = fillSideItem(toBeSimulated, getSideItem(dir), insertionMode, false);
                    if (notFilled == null || notFilled.stackSize < toBeSimulated.stackSize) {
                        sus = true;
                        break;
                    }
                }
            }
            return sus ? null : toBeSimulated;
        }
    }

    @Override
    public ItemStack removeItems(int amount, ItemStack filter, IInventoryDestination destination) {
        return invItems != null ? invItems.removeItems(amount, filter, destination) : null;
    }

    @Override
    public ItemStack simulateRemove(int amount, ItemStack filter, IInventoryDestination destination) {
        return invItems != null ? invItems.simulateRemove(amount, filter, destination) : null;
    }

    @Override
    public ItemStack removeSimilarItems(int amount, ItemStack filter, FuzzyMode fuzzyMode,
            IInventoryDestination destination) {
        return invItems != null ? invItems.removeSimilarItems(amount, filter, fuzzyMode, destination) : null;
    }

    @Override
    public ItemStack simulateSimilarRemove(int amount, ItemStack filter, FuzzyMode fuzzyMode,
            IInventoryDestination destination) {
        return invItems != null ? invItems.simulateSimilarRemove(amount, filter, fuzzyMode, destination) : null;
    }

    @Override
    public boolean containsItems() {
        if (!this.onmi) {
            // If there is no fluid tank or item inventory, it shouldn't send stuff here.
            return checkItemFluids(this.invFluids, this.invItems, this.side) > 0;
        }
        boolean anyValid = false;
        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            // Avoid sending stuff into itself me network
            if (checkValidSide(this.posInterface.getOffSet(dir).getTileEntity(), dir)) {
                final int result = checkItemFluids(this.getSideFluid(dir), this.getSideItem(dir), dir.getOpposite());
                if (result == 1) {
                    return true;
                }
                if (result != 2) {
                    anyValid = true;
                }
            }
        }
        // Same here. if there is no fluid tank or item inventory existed, it shouldn't send stuff here.
        return !anyValid;
    }

    public boolean hasSlots() {
        return (invFluids != null && invFluids.getTankInfo(side).length > 0) || (invItems != null);
    }

    @Override
    public Iterator<ItemSlot> iterator() {
        return new SlotIterator(
                invFluids != null ? invFluids.getTankInfo(side) : new FluidTankInfo[0],
                invItems != null ? invItems.iterator() : Collections.emptyIterator());
    }

    private IFluidHandler getSideFluid(ForgeDirection direction) {
        TileEntity te = this.posInterface.getOffSet(direction).getTileEntity();
        if (te instanceof IFluidHandler) {
            return (IFluidHandler) te;
        }
        return null;
    }

    private int fillSideFluid(FluidStack fluid, IFluidHandler tank, ForgeDirection direction, boolean doFill) {
        if (tank != null) {
            return tank.fill(direction, fluid, doFill);
        }
        return 0;
    }

    // EIO conduit isn't considered here
    private ItemStack fillSideItem(ItemStack item, InventoryAdaptor inv, InsertionMode mode, boolean doFill) {
        if (inv != null) {
            if (doFill) {
                return inv.addItems(item, mode);
            } else {
                return inv.simulateAdd(item, mode);
            }
        }
        return item;
    }

    private boolean isConduit(TileEntity te) {
        return ModAndClassUtil.COFH && te instanceof IItemDuct;
    }

    private ItemStack fillEIOConduit(ItemStack item, TileEntity te, ForgeDirection direction) {
        if (isConduit(te)) {
            return ((IItemDuct) te).insertItem(direction, item);
        }
        return item;
    }

    private InventoryAdaptor getSideItem(ForgeDirection direction) {
        TileEntity te = this.posInterface.getOffSet(direction).getTileEntity();
        return InventoryAdaptor.getAdaptor(te, direction.getOpposite());
    }

    // 0 - It is empty
    // 1 - It contains item/fluid
    // 2 - It doesn't exist
    private int checkItemFluids(IFluidHandler tank, InventoryAdaptor inv, ForgeDirection direction) {
        if (tank == null && inv == null) {
            return 2;
        }
        if (tank != null && tank.getTankInfo(direction) != null) {
            List<FluidTankInfo[]> tankInfos = new LinkedList<>();
            if (Util.getPart(tank, direction) instanceof PartP2PLiquids) {
                // read other ends of p2p for blocking mode
                PartP2PLiquids invFluidsP2P = (PartP2PLiquids) Util.getPart(tank, direction);
                try {
                    Iterator<PartP2PLiquids> it = invFluidsP2P.getOutputs().iterator();
                    boolean checkedInput = false;
                    while (it.hasNext() || !checkedInput) {
                        PartP2PLiquids p2p;
                        if (it.hasNext()) {
                            p2p = it.next();
                        } else {
                            p2p = invFluidsP2P.getInput();
                            checkedInput = true;
                        }
                        if (p2p == invFluidsP2P || p2p == null) continue;
                        IFluidHandler target = Ae2Reflect.getP2PLiquidTarget(p2p);
                        if (target == null) continue;
                        tankInfos.add(target.getTankInfo(p2p.getSide().getOpposite()));
                    }
                } catch (GridAccessException ignore) {}
            } else {
                tankInfos.add(tank.getTankInfo(direction));
            }
            boolean hasTank = false;
            for (FluidTankInfo[] tankInfoArray : tankInfos) {
                for (FluidTankInfo tankInfo : tankInfoArray) {
                    hasTank = true;
                    FluidStack fluid = tankInfo.fluid;
                    if (fluid != null && fluid.amount > 0) {
                        return 1;
                    }
                }
            }
            if (!hasTank && inv == null) {
                return 2;
            }
        }
        return inv != null && inv.containsItems() ? 1 : 0;
    }

    private boolean checkValidSide(TileEntity te, ForgeDirection direction) {
        return isDifferentGrid(getInterfaceTE(te, direction.getOpposite()));
    }

    private static IInterfaceHost getInterfaceTE(TileEntity te, ForgeDirection face) {
        if (te instanceof IInterfaceHost) {
            return (IInterfaceHost) te;
        } else if (te instanceof TileCableBus) {
            IPart part = ((TileCableBus) te).getPart(face);
            if (part instanceof IInterfaceHost) {
                return (IInterfaceHost) part;
            }
        }
        return null;
    }

    private boolean isDifferentGrid(IInterfaceHost target) {
        if (this.selfInterface != null && target != null) {
            DualityInterface other = target.getInterfaceDuality();
            DualityInterface self = this.selfInterface.getInterfaceDuality();
            try {
                AENetworkProxy proxy1 = Ae2Reflect.getInterfaceProxy(other);
                AENetworkProxy proxy2 = Ae2Reflect.getInterfaceProxy(self);
                if (proxy1.getGrid() == proxy2.getGrid()) {
                    return false;
                }
            } catch (GridAccessException e) {
                return true;
            }
        }
        return true;
    }

    private static class SlotIterator implements Iterator<ItemSlot> {

        private final FluidTankInfo[] tanks;
        private final Iterator<ItemSlot> itemSlots;
        private int nextSlotIndex = 0;

        SlotIterator(FluidTankInfo[] tanks, Iterator<ItemSlot> itemSlots) {
            this.tanks = tanks;
            this.itemSlots = itemSlots;
        }

        @Override
        public boolean hasNext() {
            return nextSlotIndex < tanks.length || itemSlots.hasNext();
        }

        @Override
        public ItemSlot next() {
            if (nextSlotIndex < tanks.length) {
                FluidStack fluid = tanks[nextSlotIndex].fluid;
                ItemSlot slot = new ItemSlot();
                slot.setSlot(nextSlotIndex++);
                slot.setItemStack(fluid != null ? ItemFluidPacket.newStack(fluid) : null);
                Ae2Reflect.setItemSlotExtractable(slot, false);
                return slot;
            } else {
                ItemSlot slot = itemSlots.next();
                slot.setSlot(nextSlotIndex++);
                return slot;
            }
        }
    }
}
