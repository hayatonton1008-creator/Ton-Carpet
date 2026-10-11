package com.toncarpet.seats;

/**
 * /sit で出す「椅子」(透明なアーマースタンド)であることを表す印。
 * ArmorStandSeatMixin が ArmorStand にこのインターフェースを実装する。
 *
 * メソッド名に toncarpet$ を付けているのは、他のMODが同じ ArmorStand に似た名前のメソッドを
 * 足していても衝突しないようにするため。
 */
public interface SeatEntity {

    /** この ArmorStand が /sit の椅子か。 */
    boolean toncarpet$isSeat();

    /** 椅子にする(または戻す)。椅子にすると、当たり判定が無く透明になり、印のタグが付く(タグはセーブされる)。 */
    void toncarpet$setSeat(boolean seat);
}
