package net.osmand.plus.mapcontextmenu;

import android.content.res.Resources;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.osmand.data.Amenity;
import net.osmand.osm.AbstractPoiType;
import net.osmand.osm.MapPoiTypes;
import net.osmand.osm.PoiType;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;

import java.util.Map;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

public class InfoSectionBuilder extends BottomSectionBuilder {

	private final Amenity amenity;

	public InfoSectionBuilder(OsmandApplication app, final Amenity amenity) {
		super(app);
		this.amenity = amenity;
	}

	private void buildRow(View view, int iconId, String text) {

		LinearLayout ll = new LinearLayout(view.getContext());
		ll.setOrientation(LinearLayout.HORIZONTAL);
		LinearLayout.LayoutParams llParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) ;
		llParams.setMargins(0, dpToPx(10f), 0, dpToPx(10f));
		ll.setLayoutParams(llParams);

		// Icon
		LinearLayout llIcon = new LinearLayout(view.getContext());
		llIcon.setOrientation(LinearLayout.HORIZONTAL);
		llIcon.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(42f), ViewGroup.LayoutParams.MATCH_PARENT));
		ll.addView(llIcon);

		ImageView icon = new ImageView(view.getContext());
		LinearLayout.LayoutParams llIconParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) ;
		llIconParams.setMargins(dpToPx(12f), 0, 0, 0);
		llIconParams.gravity = Gravity.CENTER_VERTICAL;
		icon.setLayoutParams(llIconParams);
		icon.setScaleType(ImageView.ScaleType.CENTER);
		icon.setImageDrawable(getRowIcon(iconId));
		llIcon.addView(icon);

		// Text
		LinearLayout llText = new LinearLayout(view.getContext());
		llText.setOrientation(LinearLayout.VERTICAL);
		LinearLayout.LayoutParams llTextParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		llTextParams.setMargins(0, dpToPx(4f), 0, dpToPx(4f));
		llText.setLayoutParams(llTextParams);
		ll.addView(llText);

		TextView textView  = new TextView(view.getContext());

		LinearLayout.LayoutParams llTextViewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		llTextViewParams.setMargins(dpToPx(10f), 0, dpToPx(10f), 0);
		llText.setLayoutParams(llTextViewParams);
		textView.setText(text);
		//textView.setText("sdf dsaf fsdasdfg adsf asdsfd asdf sdf adsfg asdf sdfa sdf dsf agsfdgd fgsfd sdf asdf adg adf sdf asdf dfgdfsg sdfg adsf asdf asdf sdf SDF ASDF ADSF ASDF ASDF DAF SDAF dfg dsfg dfg sdfg rg rth sfghs dfgs dfgsdfg adfg dfg sdfg dfs ");
		llText.addView(textView);

		((LinearLayout)view).addView(ll);

		View horizontalLine = new View(view.getContext());
		LinearLayout.LayoutParams llHorLineParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1f));
		llHorLineParams.gravity = Gravity.BOTTOM;
		horizontalLine.setLayoutParams(llHorLineParams);
		TypedValue typedValue = new TypedValue();
		Resources.Theme theme = view.getContext().getTheme();
		theme.resolveAttribute(R.attr.dashboard_divider, typedValue, true);
		int color = typedValue.data;
		horizontalLine.setBackgroundColor(color);

		((LinearLayout)view).addView(horizontalLine);
	}

	public int dpToPx(float dp) {
		Resources r = app.getResources();
		return (int) TypedValue.applyDimension(
				COMPLEX_UNIT_DIP,
				dp,
				r.getDisplayMetrics()
		);
	}

	@Override
	public void buildSection(View view) {

		MapPoiTypes poiTypes = app.getPoiTypes();
		for(Map.Entry<String, String> e : amenity.getAdditionalInfo().entrySet()) {
			int iconId = 0;
			String key = e.getKey();
			String vl = e.getValue();
			if(key.startsWith("name:")) {
				continue;
			} else if(Amenity.OPENING_HOURS.equals(key)) {
				iconId = R.drawable.mm_clock; // todo: change icon
			} else if(Amenity.PHONE.equals(key)) {
				iconId = R.drawable.mm_amenity_telephone; // todo: change icon
			} else if(Amenity.WEBSITE.equals(key)) {
				iconId = R.drawable.mm_internet_access; // todo: change icon
			} else {
				iconId = R.drawable.ic_type_info; // todo: change icon
				AbstractPoiType pt = poiTypes.getAnyPoiAdditionalTypeByKey(e.getKey());
				if (pt != null) {
					if(pt instanceof PoiType && !((PoiType) pt).isText()) {
						vl = pt.getTranslation();
					} else {
						vl = /*pt.getTranslation() + ": " + */amenity.unzipContent(e.getValue());
					}
				} else {
					vl = /*Algorithms.capitalizeFirstLetterAndLowercase(e.getKey()) +
							": " + */amenity.unzipContent(e.getValue());
				}
			}

			buildRow(view, iconId, vl);
		}
	}
}
