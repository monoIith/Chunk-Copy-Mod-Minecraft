package dev.chunkcopy;

import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

public final class ChunkCopyIds {
    public static final String MOD_ID = "chunkcopy";
    public static final TagKey<Block> PROTECTED_BLOCKS = TagKey.of(
            RegistryKeys.BLOCK,
            Identifier.of(MOD_ID, "protected")
    );

    private ChunkCopyIds() {
    }
}
