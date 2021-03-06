package thieving_mod.handlers;

import basemod.ReflectionHacks;
import com.badlogic.gdx.Gdx;
import com.evacipated.cardcrawl.modthespire.lib.*;
import com.evacipated.cardcrawl.modthespire.patcher.PatchingException;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.ShopRoom;
import com.megacrit.cardcrawl.shop.Merchant;
import com.megacrit.cardcrawl.ui.buttons.ProceedButton;
import com.megacrit.cardcrawl.ui.panels.TopPanel;
import com.megacrit.cardcrawl.vfx.*;
import com.megacrit.cardcrawl.vfx.combat.StunStarEffect;
import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import thieving_mod.*;
import thieving_mod.enums.DialoguePool;
import thieving_mod.enums.Punishment;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.LinkedList;

public class CutsceneHandler {
    private static final LinkedList<Dialogue> dialogueQueue = new LinkedList<>();
    private static float currentDialogueTimeLeft;
    private static float starsEffectTimer;
    public static boolean showProceedButton = false;
    // public static boolean dontInitializeInflameEffect = false;

    // Hide proceed button while dialogue in progress
    @SpirePatch(
            clz = ProceedButton.class,
            method = "update"
    )
    public static class DisableProceedButtonPatch {
        @SpirePrefixPatch
        public static void Prefix(ProceedButton __instance) {
            if (ShopliftingHandler.isPlayerKickedOut && !showProceedButton) {
                __instance.hide();
            }
        }
    }

    // Prevent opening map screen while dialogue in progress
    @SpirePatch(
            clz = TopPanel.class,
            method = "updateButtons"
    )
    public static class DisableMapButtonPatch {
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall m) throws CannotCompileException {
                    if (m.getMethodName().equals("updateMapButtonLogic")) {
                        m.replace("if(!" + ShopliftingHandler.class.getName() + ".isPlayerKickedOut || " +
                                CutsceneHandler.class.getName() + ".showProceedButton){$_ = $proceed($$);}");
                    }
                }
            };
        }
    }

/*
    // Don't initialize inflame effect if we want to set its x and y pos manually
    @SpirePatch(
            clz = InflameEffect.class,
            method = SpirePatch.CONSTRUCTOR
    )
    public static class InflamePatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> patch(InflameEffect __instance) {
            if (dontInitializeInflameEffect) {
                return SpireReturn.Return(null);
            }
            return SpireReturn.Continue();
        }
    }
*/

    // Mute merchant and play our own automatic dialogue
    @SpirePatch(
            clz = Merchant.class,
            method = "update"
    )
    public static class CustomMerchantDialoguePatch {
        @SpireInsertPatch(
                locator = SpeechTimerUpdateLocator.class
        )
        public static void Insert(Merchant __instance) {
            if (ShopliftingHandler.isPlayerKickedOut) {
                // Freeze the merchant's speech timer when kicked out (undo the decrement)
                float speechTimer = (float) ReflectionHacks.getPrivate(__instance, Merchant.class, "speechTimer");
                speechTimer += Gdx.graphics.getDeltaTime();
                ReflectionHacks.setPrivate(__instance, Merchant.class, "speechTimer", speechTimer);

                if (currentDialogueTimeLeft > 0) {
                    // Update custom speech timer
                    currentDialogueTimeLeft -= Gdx.graphics.getDeltaTime();
                    // Update stars timer and effect
                    if(starsEffectTimer <= 0){
                        starsEffectTimer = 0.67f;
                        float x = AbstractDungeon.player.hb.cX;
                        float y = AbstractDungeon.player.hb.cY + AbstractDungeon.player.hb.height/2;
                        AbstractDungeon.effectsQueue.add(new StunStarEffect(x, y));
                    }else{
                        starsEffectTimer -= Gdx.graphics.getDeltaTime();
                    }
                } else {
                    if (!dialogueQueue.isEmpty()) {
                        // Once time runs out, make merchant talk
                        Dialogue dialogue = dialogueQueue.poll();
                        assert dialogue != null;
                        Merchant merchant = ((ShopRoom) (AbstractDungeon.getCurrRoom())).merchant;
                        AbstractDungeon.effectList.add(new SpeechBubble(merchant.hb.cX - 50.0F * Settings.scale, merchant.hb.cY + 70.0F * Settings.scale, 3.0F,
                                dialogue.text, false));
                        // Play his sfx
                        CardCrawlGame.sound.play(dialogue.sfxKey);
                        // Play vfx
                        for (Effect effect : dialogue.effects) {
                            float x = -1, y = -1;
                            switch (effect.entity) {
                                case PLAYER:
                                    x = AbstractDungeon.player.hb.cX;
                                    y = AbstractDungeon.player.hb.cY;
                                    break;
                                case MERCHANT:
                                    x = merchant.hb.cX;
                                    y = merchant.hb.cY;
                                    break;
                            }
                            try {
                                AbstractGameEffect vfx = (AbstractGameEffect) (effect.effect.getDeclaredConstructors()[0].newInstance(x, y));
                                AbstractDungeon.topLevelEffectsQueue.add(vfx);
                            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                                e.printStackTrace();
                            }
                        }
                        // Reset timer
                        currentDialogueTimeLeft = dialogue.duration;
                    } else if (!PunishmentHandler.isPunishmentIssued) {
                        // After dialogue is finally completed, apply the punishment
                        PunishmentHandler.issuePunishment();
                    }
                }
            }
        }

        private static class SpeechTimerUpdateLocator extends SpireInsertLocator {
            public int[] Locate(CtBehavior ctMethodToPatch) throws CannotCompileException, PatchingException {
                Matcher matcher = new Matcher.FieldAccessMatcher(Merchant.class, "speechTimer");
                return LineFinder.findInOrder(ctMethodToPatch, matcher);
            }
        }
    }

    /**
     * Chooses a random dialogue from the pool
     */
    public static void enqueueMerchantDialogue(DialoguePool dialoguePool) {
        int index = ThievingMod.random.nextInt(dialoguePool.values.length);
        dialogueQueue.add(new Dialogue(dialoguePool.values[index].text, dialoguePool.values[index].duration));
    }

    /**
     * Enqueue all the dialogue for that punishment
     *
     * @param punishment Contains the merchant dialogue
     */
    public static void enqueueMerchantDialogue(Punishment punishment) {
        dialogueQueue.addAll(Arrays.asList(punishment.dialogue));
    }

    /**
     * Determines if shopkeeper has stopped talking or not
     * Used to tell if we can click on the shopkeeper for dialogue
     */
    public static boolean isDialogueFinished() {
        return currentDialogueTimeLeft <= 0 && dialogueQueue.isEmpty();
    }

    public static void reset() {
        currentDialogueTimeLeft = 0;
        starsEffectTimer = 0;
        dialogueQueue.clear();
    }
}
