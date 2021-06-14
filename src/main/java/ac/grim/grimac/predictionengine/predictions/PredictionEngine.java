package ac.grim.grimac.predictionengine.predictions;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.movementTick.MovementTickerPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.enums.MoverType;
import ac.grim.grimac.utils.nmsImplementations.Collisions;
import ac.grim.grimac.utils.nmsImplementations.JumpPower;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class PredictionEngine {
    boolean canRiptide = false;

    public void guessBestMovement(float speed, GrimPlayer player) {
        player.speed = speed;
        double bestInput = Double.MAX_VALUE;

        List<VectorData> possibleVelocities = multiplyPossibilitiesByInputs(player, fetchPossibleInputs(player), speed);

        // This is an optimization - sort the inputs by the most likely first to stop running unneeded collisions
        possibleVelocities.sort((a, b) -> compareDistanceToActualMovement(a.vector, b.vector, player));
        possibleVelocities.sort(this::putVelocityExplosionsFirst);

        // Other checks will catch ground spoofing - determine if the player can make an input below 0.03
        player.couldSkipTick = false;
        if (player.onGround) {
            possibleVelocities.forEach((a) -> player.couldSkipTick = player.couldSkipTick || a.vector.getX() * a.vector.getX() + a.vector.getZ() * a.vector.getZ() < 9.0E-4D);
        } else {
            possibleVelocities.forEach((a) -> player.couldSkipTick = player.couldSkipTick || a.vector.getX() * a.vector.getX() + a.vector.getY() * a.vector.getY() + a.vector.getZ() + a.vector.getZ() < 9.0E-4D);
        }

        VectorData bestCollisionVel = null;
        Vector beforeCollisionMovement = null;

        for (VectorData clientVelAfterInput : possibleVelocities) {
            Vector backOff = Collisions.maybeBackOffFromEdge(clientVelAfterInput.vector, MoverType.SELF, player);
            Vector additionalPushMovement = handlePushMovement(player, backOff);
            Vector outputVel = Collisions.collide(player, additionalPushMovement.getX(), additionalPushMovement.getY(), additionalPushMovement.getZ());
            double resultAccuracy = outputVel.distance(player.actualMovement);

            if (resultAccuracy < bestInput) {
                bestInput = resultAccuracy;
                player.clientVelocity = backOff.clone();
                beforeCollisionMovement = additionalPushMovement;
                bestCollisionVel = new VectorData(outputVel.clone(), clientVelAfterInput, VectorData.VectorType.BestVelPicked);

                // Optimization - Close enough, other inputs won't get closer
                // This works as velocity is ran first
                if (resultAccuracy < 0.01) break;
            }
        }

        new MovementTickerPlayer(player).move(MoverType.SELF, beforeCollisionMovement, bestCollisionVel.vector);
        player.predictedVelocity = bestCollisionVel;
        endOfTick(player, player.gravity, player.friction);
    }

    public List<VectorData> multiplyPossibilitiesByInputs(GrimPlayer player, Set<VectorData> possibleVectors, float speed) {
        List<VectorData> returnVectors = new ArrayList<>();
        loopVectors(player, possibleVectors, speed, returnVectors);

        // There is a bug where the player sends sprinting, thinks they are sprinting, server also thinks so, but they don't have sprinting speed
        // It mostly occurs when the player takes damage.
        // This isn't going to destroy predictions as sprinting uses 1/3 the number of inputs, now 2/3 with this hack
        // Meaning there is still a 1/3 improvement for sprinting players over non-sprinting
        // If a player in this glitched state lets go of moving forward, then become un-glitched
        if (player.isSprinting) {
            player.isSprinting = false;
            speed /= 1.3D;
            loopVectors(player, possibleVectors, speed, returnVectors);
            player.isSprinting = true;
        }

        return returnVectors;
    }

    public Set<VectorData> fetchPossibleInputs(GrimPlayer player) {
        Set<VectorData> velocities = player.getPossibleVelocities();

        addAdditionToPossibleVectors(player, velocities);
        addJumpsToPossibilities(player, velocities);

        return velocities;
    }

    public int compareDistanceToActualMovement(Vector a, Vector b, GrimPlayer player) {
        double x = player.actualMovement.getX();
        double y = player.actualMovement.getY();
        double z = player.actualMovement.getZ();

        // Weight y distance heavily to avoid jumping when we shouldn't be jumping, as it affects later ticks.
        double distance1 = Math.pow(a.getX() - x, 2) + Math.pow(a.getY() - y, 2) * 5 + Math.pow(a.getZ() - z, 2);
        double distance2 = Math.pow(b.getX() - x, 2) + Math.pow(b.getY() - y, 2) * 5 + Math.pow(b.getZ() - z, 2);

        return Double.compare(distance1, distance2);
    }

    public int putVelocityExplosionsFirst(VectorData a, VectorData b) {
        int aScore = 0;
        int bScore = 0;
        if (a.hasVectorType(VectorData.VectorType.Explosion))
            aScore++;

        if (a.hasVectorType(VectorData.VectorType.Knockback))
            aScore++;

        if (b.hasVectorType(VectorData.VectorType.Explosion))
            bScore++;

        if (b.hasVectorType(VectorData.VectorType.Knockback))
            bScore++;

        return Integer.compare(aScore, bScore);
    }


    // Currently, we cannot handle player being pushed by pistons while starting riptides while on the ground
    // I'll be very surprised if someone actually manages to accomplish this
    public Vector handlePushMovement(GrimPlayer player, Vector vector) {
        if (player.uncertaintyHandler.pistonX != 0 || player.uncertaintyHandler.pistonY != 0 || player.uncertaintyHandler.pistonZ != 0) {
            // Fixes issue occuring when pushed upwards and standing on piston
            // Subtracting gravity allows the player's y vel to be set to 0
            //
            // 0.03 for < 0.03 movement thing, 0.01 for pistons pushing past 0.01, 0.01 extra for rounding or something
            if (player.uncertaintyHandler.pistonY != 0 && Math.abs(player.y - player.lastY) < 0.05) {
                player.uncertaintyHandler.pistonGravityHack = true;
            }

            return PredictionEngineElytra.cutVectorsToPlayerMovement(player.actualMovement,
                    vector.clone().add(new Vector(player.uncertaintyHandler.pistonX, player.uncertaintyHandler.pistonY, player.uncertaintyHandler.pistonZ).multiply(-1)),
                    vector.clone().add(new Vector(player.uncertaintyHandler.pistonX, player.uncertaintyHandler.pistonY, player.uncertaintyHandler.pistonZ)));
        }

        if (player.canGroundRiptide) {
            SimpleCollisionBox box = new SimpleCollisionBox(vector, vector.clone().add(new Vector(0.0D, 1.1999999F, 0.0D)));

            return PredictionEngineElytra.cutVectorsToPlayerMovement(player.actualMovement,
                    new Vector(box.minX, box.minY, box.minZ),
                    new Vector(box.maxX, box.maxY, box.maxZ));
        }

        return vector;
    }

    public void endOfTick(GrimPlayer player, double d, float friction) {
        player.clientVelocitySwimHop = null;
        if (canSwimHop(player)) {
            player.clientVelocitySwimHop = player.clientVelocity.clone().setY(0.3);
        }
    }

    private void loopVectors(GrimPlayer player, Set<VectorData> possibleVectors, float speed, List<VectorData> returnVectors) {
        // Stop omni-sprint
        // Optimization - Also cuts down scenarios by 2/3
        int zMin = player.isSprinting ? 1 : -1;

        for (VectorData possibleLastTickOutput : possibleVectors) {
            for (int x = -1; x <= 1; x++) {
                for (int z = zMin; z <= 1; z++) {
                    VectorData result = new VectorData(possibleLastTickOutput.vector.clone().add(getMovementResultFromInput(player, transformInputsToVector(player, new Vector(x, 0, z)), speed, player.xRot)), possibleLastTickOutput, VectorData.VectorType.InputResult);
                    result = result.setVector(handleMovementLenience(player, result.vector.clone()), VectorData.VectorType.Lenience);
                    result = result.setVector(result.vector.clone().multiply(player.stuckSpeedMultiplier), VectorData.VectorType.StuckMultiplier);
                    result = result.setVector(handleOnClimbable(result.vector.clone(), player), VectorData.VectorType.Climbable);
                    returnVectors.add(result);
                }
            }
        }
    }

    public void addAdditionToPossibleVectors(GrimPlayer player, Set<VectorData> existingVelocities) {
        for (VectorData vector : new HashSet<>(existingVelocities)) {
            if (player.knownExplosion != null) {
                existingVelocities.add(new VectorData(vector.vector.clone().add(player.knownExplosion.vector), vector, VectorData.VectorType.Explosion));
            }

            if (player.firstBreadExplosion != null) {
                existingVelocities.add(new VectorData(vector.vector.clone().add(player.firstBreadExplosion.vector), vector, VectorData.VectorType.Explosion));
            }

            if (player.compensatedRiptide.getCanRiptide()) {
                ItemStack main = player.bukkitPlayer.getInventory().getItemInMainHand();
                ItemStack off = player.bukkitPlayer.getInventory().getItemInOffHand();

                int j;
                if (main.getType() == Material.TRIDENT) {
                    j = main.getEnchantmentLevel(Enchantment.RIPTIDE);
                } else if (off.getType() == Material.TRIDENT) {
                    j = off.getEnchantmentLevel(Enchantment.RIPTIDE);
                } else {
                    return;
                }

                canRiptide = true;

                float f7 = player.xRot;
                float f = player.yRot;
                float f1 = -player.trigHandler.sin(f7 * ((float) Math.PI / 180F)) * player.trigHandler.cos(f * ((float) Math.PI / 180F));
                float f2 = -player.trigHandler.sin(f * ((float) Math.PI / 180F));
                float f3 = player.trigHandler.cos(f7 * ((float) Math.PI / 180F)) * player.trigHandler.cos(f * ((float) Math.PI / 180F));
                float f4 = (float) Math.sqrt(f1 * f1 + f2 * f2 + f3 * f3);
                float f5 = 3.0F * ((1.0F + (float) j) / 4.0F);
                f1 = f1 * (f5 / f4);
                f2 = f2 * (f5 / f4);
                f3 = f3 * (f5 / f4);

                existingVelocities.add(new VectorData(vector.vector.clone().add(new Vector(f1, f2, f3)), VectorData.VectorType.Trident));
            }
        }
    }

    public void addJumpsToPossibilities(GrimPlayer player, Set<VectorData> existingVelocities) {
    }

    public boolean canSwimHop(GrimPlayer player) {
        boolean canCollideHorizontally = !Collisions.isEmpty(player, player.boundingBox.copy().expand(0.1, -0.01, 0.1));
        boolean inWater = player.compensatedWorld.containsLiquid(player.boundingBox.copy().expand(0.1, 0.1, 0.1));

        // Vanilla system ->
        // Requirement 1 - The player must be in water or lava
        // Requirement 2 - The player must have X position + X movement, Y position + Y movement - Y position before tick + 0.6, Z position + Z movement have no collision
        // Requirement 3 - The player must have horizontal collision

        // Our system ->
        // Requirement 1 - The player must be within 0.1 blocks of water or lava (which is why this is base and not PredictionEngineWater/Lava)
        // Requirement 2 - The player must have something to collide with within 0.1 blocks

        // Why remove the empty check?  The real movement is hidden due to the horizontal collision
        // For example, a 1.14+ player can have a velocity of (10000, 0, 0) and if they are against a wall,
        // We only see the (0,0,0) velocity.
        // This means it is impossible to accurately create the requirement of no collision.
        // Oh well, I guess this could allow some Jesus bypasses next to a wall that has multiple blocks
        // But it's faster to swim anyways on 1.13+, and faster to just go on land in 1.12-

        return canCollideHorizontally && inWater;
    }

    // This is just the vanilla equation, which accepts invalid inputs greater than 1
    // We need it because of collision support when a player is using speed
    public Vector getMovementResultFromInput(GrimPlayer player, Vector inputVector, float f, float f2) {
        float f3 = player.trigHandler.sin(f2 * 0.017453292f);
        float f4 = player.trigHandler.cos(f2 * 0.017453292f);

        double xResult = inputVector.getX() * f4 - inputVector.getZ() * f3;
        double zResult = inputVector.getZ() * f4 + inputVector.getX() * f3;

        return new Vector(xResult * f, 0, zResult * f);
    }

    public static Vector transformInputsToVector(GrimPlayer player, Vector theoreticalInput) {
        float bestPossibleX;
        float bestPossibleZ;

        // We save the slow movement status as it's easier and takes less CPU than recalculating it with newly stored old values
        if (player.isSlowMovement) {
            bestPossibleX = Math.min(Math.max(-1, Math.round(theoreticalInput.getX() / 0.3)), 1) * 0.3f;
            bestPossibleZ = Math.min(Math.max(-1, Math.round(theoreticalInput.getZ() / 0.3)), 1) * 0.3f;
        } else {
            bestPossibleX = Math.min(Math.max(-1, Math.round(theoreticalInput.getX())), 1);
            bestPossibleZ = Math.min(Math.max(-1, Math.round(theoreticalInput.getZ())), 1);
        }

        Vector inputVector = new Vector(bestPossibleX, 0, bestPossibleZ);
        inputVector.multiply(0.98);

        if (inputVector.lengthSquared() > 1) inputVector.normalize();

        return inputVector;
    }

    private Vector handleMovementLenience(GrimPlayer player, Vector vector) {
        int maxFireworks = player.compensatedFireworks.getMaxFireworksAppliedPossible() * 2;

        if (maxFireworks <= 0) return vector;
        if (!player.isGliding) return vector;

        Vector currentLook = PredictionEngineElytra.getVectorForRotation(player, player.yRot, player.xRot);
        Vector lastLook = PredictionEngineElytra.getVectorForRotation(player, player.lastYRot, player.lastXRot);

        Vector boostOne = vector.clone();
        Vector boostTwo = vector.clone();

        for (int i = 0; i < maxFireworks; i++) {
            boostOne.add(new Vector(currentLook.getX() * 0.1 + (currentLook.getX() * 1.5 - boostOne.getX()) * 0.5, currentLook.getY() * 0.1 + (currentLook.getY() * 1.5 - boostOne.getY()) * 0.5, (currentLook.getZ() * 0.1 + (currentLook.getZ() * 1.5 - boostOne.getZ()) * 0.5)));
            boostTwo.add(new Vector(lastLook.getX() * 0.1 + (lastLook.getX() * 1.5 - boostTwo.getX()) * 0.5, lastLook.getY() * 0.1 + (lastLook.getY() * 1.5 - boostTwo.getY()) * 0.5, (lastLook.getZ() * 0.1 + (lastLook.getZ() * 1.5 - boostTwo.getZ()) * 0.5)));
        }

        SimpleCollisionBox box = new SimpleCollisionBox(boostOne, boostTwo);

        if (box.minX > vector.getX()) {
            box.minX = vector.getX();
        } else if (box.maxX < vector.getX()) {
            box.maxX = vector.getX();
        }

        if (box.minY > vector.getY()) {
            box.minY = vector.getY();
        } else if (box.maxY < vector.getY()) {
            box.maxY = vector.getY();
        }

        if (box.minZ > vector.getZ()) {
            box.minZ = vector.getZ();
        } else if (box.maxZ < vector.getZ()) {
            box.maxZ = vector.getZ();
        }

        return PredictionEngineElytra.cutVectorsToPlayerMovement(player.actualMovement,
                new Vector(box.minX, box.minY, box.minZ),
                new Vector(box.maxX, box.maxY, box.maxZ));
    }

    public Vector handleOnClimbable(Vector vector, GrimPlayer player) {
        return vector;
    }

    public void doJump(GrimPlayer player, Vector vector) {
        JumpPower.jumpFromGround(player, vector);
    }
}