
/**
 * @author  Natasa Tsantali
 */
public class Movie {
	
	public static final int CHILDRENS = 2;
	public static final int REGULAR = 0;
	public static final int NEW_RELEASE = 1;
	
	private String _title;
	/**
	 * @uml.property  name="_price"
	 * @uml.associationEnd  
	 */
	private Price _price;
	
	public Movie(String title, int priceCode) {
		_title = title;
		setPriceCode(priceCode);
	}
	
	public int getPriceCode() {
		return _price.getPriceCode();
	}
	
	public void setPriceCode(int arg) {
		switch(arg) {
            case REGULAR:
                _price = new RegularPrice();
                  break;
            case NEW_RELEASE:
                _price = new NewReleasePrice();
                break;
            case CHILDRENS:
                _price = new ChildrensPrice();
                break;
            }
	}
	
	public String getTitle() {
		return _title;
	}

    public double getCharge(int _daysRented) {
        return _price.getCharge(_daysRented);
    }

    public int getFrequentRenterPoints(int _daysRented) {
        return _price.getFrequentRenterPoints(_daysRented);
    }

}
