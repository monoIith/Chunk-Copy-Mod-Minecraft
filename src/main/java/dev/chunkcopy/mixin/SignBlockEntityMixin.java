package dev.chunkcopy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.chunkcopy.ChunkCopyMod;
import dev.chunkcopy.replication.PlayerActionContext;
import dev.chunkcopy.replication.SanitizedBlockEntityData;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.filter.FilteredMessage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;
import java.util.Optional;

@Mixin(SignBlockEntity.class)
abstract class SignBlockEntityMixin {
    @WrapMethod(method = "tryChangeText")
    private void chunkcopy$mirrorEditedText(
            PlayerEntity player,
            boolean front,
            List<FilteredMessage> messages,
            Operation<Void> original
    ) {
        SignBlockEntity self = (SignBlockEntity) (Object) this;
        Optional<SanitizedBlockEntityData> before = self.getWorld() instanceof ServerWorld serverWorld
                ? SanitizedBlockEntityData.capture(serverWorld, self.getPos())
                : Optional.empty();
        original.call(player, front, messages);
        if (player instanceof ServerPlayerEntity serverPlayer
                && self.getWorld() instanceof ServerWorld serverWorld
                && !before.equals(SanitizedBlockEntityData.capture(serverWorld, self.getPos()))) {
            ChunkCopyMod.service().acceptDisplayMetadata(serverWorld, self.getPos(), serverPlayer);
        }
    }

    @WrapMethod(method = "setText")
    private boolean chunkcopy$captureTextMutation(SignText text, boolean front, Operation<Boolean> original) {
        boolean changed = original.call(text, front);
        if (changed) {
            chunkcopy$touchActionMetadata();
        }
        return changed;
    }

    @WrapMethod(method = "setWaxed")
    private boolean chunkcopy$captureWaxMutation(boolean waxed, Operation<Boolean> original) {
        boolean changed = original.call(waxed);
        if (changed) {
            chunkcopy$touchActionMetadata();
        }
        return changed;
    }

    private void chunkcopy$touchActionMetadata() {
        SignBlockEntity self = (SignBlockEntity) (Object) this;
        if (self.getWorld() instanceof ServerWorld world) {
            PlayerActionContext.touchDisplayMetadata(world, self.getPos());
        }
    }
}
