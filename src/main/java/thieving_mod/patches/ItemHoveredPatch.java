package thieving_mod.patches;

import basemod.ReflectionHacks;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.*;
import com.evacipated.cardcrawl.modthespire.patcher.PatchingException;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.shop.StorePotion;
import com.megacrit.cardcrawl.shop.StoreRelic;
import javassist.CannotCompileException;
import javassist.CtBehavior;
import thieving_mod.ThievingMod;

public class ItemHoveredPatch {
    private static Object highlightedItem;
    private static boolean isDarkBgRendered = false;
    // Hover over item listeners

    @SpirePatch(
            clz = ShopScreen.class,
            method = "update"
    )
    public static class HoverCardPatch {
        @SpireInsertPatch(
                locator = ItemHoveredLocator.class,
                localvars = {"c"}
        )
        public static void Insert(Object __instance, AbstractCard c) {
            CommonInsert(c);
        }
    }

    @SpirePatch(
            clz = StoreRelic.class,
            method = "update"
    )
    @SpirePatch(
            clz = StorePotion.class,
            method = "update"
    )
    public static class HoverPotionAndRelicPatch {
        @SpireInsertPatch(
                locator = ItemHoveredLocator.class
        )
        public static void Insert(Object __instance) {
            CommonInsert(__instance);
        }
    }

    private static void CommonInsert(Object item) {
        if (ThievingMod.isConfigKeyPressed()) {
            highlightedItem = item;
        }
    }

    private static class ItemHoveredLocator extends SpireInsertLocator {
        public int[] Locate(CtBehavior ctMethodToPatch) throws CannotCompileException, PatchingException {
            Matcher matcher = new Matcher.MethodCallMatcher(ShopScreen.class, "moveHand");
            return LineFinder.findAllInOrder(ctMethodToPatch, matcher);
        }
    }

    // Don't render the highlighted item before the black background
    @SpirePatch(
            clz = AbstractRelic.class,
            method="renderWithoutAmount"
    )
    @SpirePatch(
            clz = AbstractPotion.class,
            method="shopRender"
    )
    @SpirePatch(
            clz= AbstractCard.class,
            method="render",
            paramtypez={SpriteBatch.class}
    )
    public static class StopItemRenderPatch{
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(Object __instance){
            // Don't render the highlighted item before the dark background
            if(__instance == highlightedItem && !isDarkBgRendered){
                return SpireReturn.Return(null);
            }
            // Otherwise render like the item like normal
            return SpireReturn.Continue();
        }
    }

    // Render effects when hotkey + hover
    @SpirePatch(
            clz = CardCrawlGame.class,
            method = "render"
    )
    public static class RenderPatch {
        // How long it takes for dark background to fully show/hide
        private static final float FADE_DURATION = 0.5f;

        // Make dark bg appear
        @SpireInsertPatch(
                locator = PreRenderBlackScreenLocator.class
        )
        public static void ShowDarkBackgroundPatch(CardCrawlGame __instance) {
            // Set bg opacity
            Color screenColor = (Color) ReflectionHacks.getPrivate(__instance, CardCrawlGame.class, "screenColor");
            if (highlightedItem != null) {
                if (screenColor.a < 0.5f) {
                    screenColor.a += Gdx.graphics.getDeltaTime()/FADE_DURATION;
                    if (screenColor.a > 0.5f) {
                        screenColor.a = 0.5f;
                    }
                }
            } else if (screenColor.a > 0f) {
                screenColor.a -= Gdx.graphics.getDeltaTime()/FADE_DURATION;
                if (screenColor.a < 0f) {
                    screenColor.a = 0f;
                }
            }
        }

        // After dark bg has rendered
        @SpireInsertPatch(
                locator = PostRenderBlackScreenLocator.class
        )
        public static void RenderTooltipAndItem(CardCrawlGame __instance) {
            if (highlightedItem != null) {
                SpriteBatch sb = (SpriteBatch)ReflectionHacks.getPrivate(__instance, CardCrawlGame.class, "sb");
                // Call item render methods
                isDarkBgRendered = true;
                if (highlightedItem instanceof StoreRelic) {
                    ((StoreRelic) highlightedItem).relic.renderWithoutAmount(sb, new Color(0.0F, 0.0F, 0.0F, 0.25F));
                } else if (highlightedItem instanceof StorePotion) {
                    ((StorePotion) highlightedItem).potion.shopRender(sb);
                } else if (highlightedItem instanceof AbstractCard) {
                    ((AbstractCard) highlightedItem).render(sb);
                }
                // Show tooltip
                float x = InputHelper.mX;
                float y = InputHelper.mY - 64;
                FontHelper.renderFontLeft(sb, FontHelper.losePowerFont, "Steal item?", x, y, Color.WHITE);
            }
            // Reset flags for next render cycle
            isDarkBgRendered = false;
            highlightedItem = null;
        }

        // Render locators

        private static class PreRenderBlackScreenLocator extends SpireInsertLocator {
            public int[] Locate(CtBehavior ctMethodToPatch) throws CannotCompileException, PatchingException {
                Matcher matcher = new Matcher.MethodCallMatcher(CardCrawlGame.class, "renderBlackFadeScreen");
                return LineFinder.findAllInOrder(ctMethodToPatch, matcher);
            }
        }

        private static class PostRenderBlackScreenLocator extends SpireInsertLocator {
            public int[] Locate(CtBehavior ctMethodToPatch) throws CannotCompileException, PatchingException {
                Matcher matcher = new Matcher.MethodCallMatcher(CardCrawlGame.class, "renderBlackFadeScreen");
                int[] result = LineFinder.findAllInOrder(ctMethodToPatch, matcher);
                result[0]++;
                return result;
            }
        }
    }
}