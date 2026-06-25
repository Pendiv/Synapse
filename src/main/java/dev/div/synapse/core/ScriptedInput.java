package dev.div.synapse.core;

import net.minecraft.client.player.Input;

/**
 * An {@link Input} whose movement flags are driven programmatically instead of
 * read from the keyboard. Swapped into {@code LocalPlayer.input} for a bounded
 * number of ticks to make the player walk/strafe/jump/sneak, then restored.
 *
 * <p>Set the public flags ({@code up/down/left/right/jumping/shiftKeyDown}) once;
 * {@link #tick} recomputes the impulses from them every game tick so the motion
 * persists for the whole window.
 */
public class ScriptedInput extends Input {

    @Override
    public void tick(boolean isMovingSlowly, float strafeMultiplier) {
        this.forwardImpulse = (this.up ? 1.0F : 0.0F) - (this.down ? 1.0F : 0.0F);
        this.leftImpulse = (this.left ? 1.0F : 0.0F) - (this.right ? 1.0F : 0.0F);
        if (isMovingSlowly) {
            this.forwardImpulse *= strafeMultiplier;
            this.leftImpulse *= strafeMultiplier;
        }
    }
}
