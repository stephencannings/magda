import React from 'react';
import './Banner.css';

export default class Banner extends React.Component {
  constructor(props) {
    super(props);
    this.state = {isOpen: true};
  }

  render() {
    if(this.state.isOpen){
      return (
        <div className='banner'>
          <span>A new look for Australia&apos;s data portal: our updated site makes it easier for you to find relevant open data. You can still <a href='https://data.gov.au/'>go back</a> to the old site</span>
          <button type='button' onClick={()=>this.setState({isOpen : false})}>X</button>
        </div>
      );
    }
    return null;
  }
}
