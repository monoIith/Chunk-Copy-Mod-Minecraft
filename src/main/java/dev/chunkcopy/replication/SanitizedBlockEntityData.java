package dev.chunkcopy.replication;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.DecoratedPotBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Optional;

/** A deliberately small, display-only subset of block-entity data. */
public record SanitizedBlockEntityData(Kind kind, NbtCompound data) {
    private static final List<String> SIGN_KEYS = List.of("front_text", "back_text", "is_waxed");
    private static final List<String> BANNER_KEYS = List.of("patterns");
    private static final List<String> SKULL_KEYS = List.of("profile");
    private static final List<String> POT_KEYS = List.of("sherds");

    public SanitizedBlockEntityData {
        data = sanitize(kind, data);
    }

    @Override
    public NbtCompound data() {
        return data.copy();
    }

    private static NbtCompound sanitize(Kind kind, NbtCompound input) {
        List<String> allowed = switch (kind) {
            case SIGN -> SIGN_KEYS;
            case BANNER -> BANNER_KEYS;
            case SKULL -> SKULL_KEYS;
            case DECORATED_POT -> POT_KEYS;
        };
        NbtCompound sanitized = new NbtCompound();
        for (String key : allowed) {
            NbtElement value = input.get(key);
            if (value != null) {
                sanitized.put(key, value.copy());
            }
        }
        return sanitized;
    }

    public static Optional<SanitizedBlockEntityData> capture(ServerWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        Kind kind;
        List<String> allowed;
        if (blockEntity instanceof SignBlockEntity sign) {
            kind = Kind.SIGN;
            allowed = SIGN_KEYS;
        } else if (blockEntity instanceof BannerBlockEntity) {
            kind = Kind.BANNER;
            allowed = BANNER_KEYS;
        } else if (blockEntity instanceof SkullBlockEntity) {
            kind = Kind.SKULL;
            allowed = SKULL_KEYS;
        } else if (blockEntity instanceof DecoratedPotBlockEntity) {
            kind = Kind.DECORATED_POT;
            allowed = POT_KEYS;
        } else {
            return Optional.empty();
        }

        if (blockEntity instanceof SignBlockEntity sign) {
            NbtCompound data = new NbtCompound();
            data.put("front_text", SignText.CODEC, sanitizeSignText(sign.getFrontText()));
            data.put("back_text", SignText.CODEC, sanitizeSignText(sign.getBackText()));
            data.putBoolean("is_waxed", sign.isWaxed());
            return Optional.of(new SanitizedBlockEntityData(kind, data));
        }

        NbtCompound full = blockEntity.createNbt(world.getRegistryManager());
        NbtCompound sanitized = new NbtCompound();
        for (String key : allowed) {
            NbtElement value = full.get(key);
            if (value != null) {
                sanitized.put(key, value.copy());
            }
        }
        return Optional.of(new SanitizedBlockEntityData(kind, sanitized));
    }

    private static SignText sanitizeSignText(SignText source) {
        SignText sanitized = new SignText()
                .withColor(source.getColor())
                .withGlowing(source.isGlowing());
        for (int line = 0; line < 4; line++) {
            sanitized = sanitized.withMessage(
                    line,
                    Text.literal(source.getMessage(line, false).getString()),
                    Text.literal(source.getMessage(line, true).getString())
            );
        }
        return sanitized;
    }

    public boolean apply(ServerWorld world, BlockPos pos) {
        BlockEntity destination = world.getBlockEntity(pos);
        if (!kind.matches(destination)) {
            return false;
        }

        destination.read(NbtReadView.create(ErrorReporter.EMPTY, world.getRegistryManager(), data.copy()));
        destination.markDirty();
        BlockState state = world.getBlockState(pos);
        world.updateListeners(pos, state, state, Block.NOTIFY_LISTENERS);
        return true;
    }

    public enum Kind {
        SIGN,
        BANNER,
        SKULL,
        DECORATED_POT;

        private boolean matches(BlockEntity entity) {
            return switch (this) {
                case SIGN -> entity instanceof SignBlockEntity;
                case BANNER -> entity instanceof BannerBlockEntity;
                case SKULL -> entity instanceof SkullBlockEntity;
                case DECORATED_POT -> entity instanceof DecoratedPotBlockEntity;
            };
        }
    }
}
