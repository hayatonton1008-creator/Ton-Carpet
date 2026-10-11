package com.toncarpet.mixin;

import com.toncarpet.seats.SeatEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * /sit の椅子(透明なアーマースタンド)の動きを決める。
 *   - 当たり判定が無い(マーカー)・透明で、セーブされるタグ "toncarpet_seat" が付く
 *   - 座っているプレイヤーが降りた瞬間に、椅子を少し持ち上げて(降りたプレイヤーが地面に埋まらないように)消す
 *   - 座っている間、椅子の向きをプレイヤーの視線の向きに合わせる(体が視線について回る)
 *   - 誰も乗っていない椅子(保存されたまま残ったもの等)は、しばらくしたら自動で消す
 * 椅子かどうかはタグで覚えるので、ワールドを保存して読み込み直しても、椅子として扱われ続ける。
 */
@Mixin(ArmorStand.class)
public abstract class ArmorStandSeatMixin extends LivingEntity implements SeatEntity {

    // 椅子の印。タグはエンティティと一緒にセーブされる。
    private static final String SEAT_TAG = "toncarpet_seat";
    // 降りるとき、プレイヤーが埋まらないよう椅子を持ち上げる高さ(座るときに下げた分と同じ)
    private static final double DISMOUNT_LIFT = 0.16;
    // 乗客のいない椅子を消すまでの猶予(tick)。座った直後に乗客が付くのを待つため
    private static final int ORPHAN_GRACE_TICKS = 40;

    protected ArmorStandSeatMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Shadow
    protected abstract void setMarker(boolean marker);

    @Override
    public boolean toncarpet$isSeat() {
        return this.getTags().contains(SEAT_TAG);
    }

    @Override
    public void toncarpet$setSeat(boolean seat) {
        if (seat) {
            this.addTag(SEAT_TAG);
        } else {
            this.removeTag(SEAT_TAG);
        }
        this.setMarker(seat);
        this.setInvisible(seat);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        if (this.toncarpet$isSeat()) {
            // 降りるプレイヤーの位置は椅子の位置を基準に決まるので、先に持ち上げてから椅子を消す
            this.setPos(this.getX(), this.getY() + DISMOUNT_LIFT, this.getZ());
            this.discard();
        }
        super.removePassenger(passenger);
    }

    /**
     * 毎tick、椅子の向きを乗っているプレイヤーに合わせる。
     * ArmorStand は tick() を自分では定義していない(LivingEntity から受け継いでいる)ので、@Inject では
     * 対象が見つからない。代わりに、この Mixin が tick() を上書きして、元の処理(super.tick())を呼ぶ。
     */
    @Override
    public void tick() {
        super.tick();
        if (!this.toncarpet$isSeat()) {
            return;
        }
        Entity rider = this.getFirstPassenger();
        if (rider != null) {
            this.setYRot(rider.getYHeadRot());
        } else if (this.tickCount > ORPHAN_GRACE_TICKS) {
            this.discard(); // 誰も乗っていない椅子は残さない
        }
    }
}
