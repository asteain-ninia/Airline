package com.asteain.airline.pathing;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Keeps airline.sable.mixins.json switched off when Sable is not installed.
 * neoforge.mods.toml already says requiredMods=["sable"]; this is the second lock for loaders that do not read that key.
 * Loaded very early: must not touch Minecraft, MineColonies or Sable classes.
 */
public final class SableMixinPlugin implements IMixinConfigPlugin {
    private boolean sablePresent;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            sablePresent = LoadingModList.get().getModFileById("sable") != null;
        } catch (RuntimeException e) {
            sablePresent = false;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return sablePresent;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
