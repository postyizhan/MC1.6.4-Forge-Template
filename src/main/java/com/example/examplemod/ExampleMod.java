package com.example.examplemod;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(modid = ExampleMod.MODID, version = ExampleMod.VERSION, name = ExampleMod.NAME)
public class ExampleMod {
    public static final String MODID = "examplemod";
    public static final String NAME = "Example Mod";
    public static final String VERSION = "1.0.0";

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // 模组预初始化阶段
        // 在这里进行配置文件读取、方块/物品注册等
        System.out.println("Example Mod PreInit");
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        // 模组初始化阶段
        // 在这里进行合成表注册、事件处理器注册等
        System.out.println("Example Mod Init");
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        // 模组后初始化阶段
        // 在这里进行模组间交互等
        System.out.println("Example Mod PostInit");
    }
}
